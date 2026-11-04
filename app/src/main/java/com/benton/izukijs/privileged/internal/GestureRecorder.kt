package com.benton.izukijs.privileged.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.service.UnlockRecordingParser
import com.benton.izukijs.service.UnlockResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 真实锁屏录制：锁屏 -> 自动亮屏 -> 直读 /dev/input -> keyguard 消失即收尾。
 *
 * 与旧 Shell 版最大的区别：
 * - 亮屏后立刻开始采集，不会漏掉用户第一段上滑；
 * - 二进制 evdev 读取，不经过 getevent 文本格式；
 * - 录制结果带逻辑屏幕尺寸与旋转，回放时按逻辑坐标还原。
 */
internal class GestureRecorder(
    private val context: Context?,
    private val controller: WakeUnlockController,
) {

    private val resultLock = Any()

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var cancelSignal = CountDownLatch(1)

    @Volatile
    private var resultJson: String = IDLE_JSON

    @Synchronized
    fun start(timeoutMs: Int) {
        val previous = worker
        if (previous?.isAlive == true && !cancelSignal.isCancelled()) {
            PrivilegedLog.w("GestureRecorder: already recording")
            return
        }
        val signal = CountDownLatch(1)
        cancelSignal = signal
        synchronized(resultLock) { resultJson = RECORDING_JSON }
        val budget = if (timeoutMs > 0) timeoutMs.toLong() else DEFAULT_TIMEOUT_MS
        val deadline = SystemClock.elapsedRealtime() + budget
        worker = Thread({
            if (previous?.isAlive == true) {
                previous.join(PREVIOUS_JOIN_MS)
            }
            val result = keepCpuAwake(budget + CPU_LOCK_EXTRA_MS) {
                runCatching { record(deadline, signal) }.getOrElse {
                    PrivilegedLog.e("GestureRecorder: record failed", it)
                    encodeFailed(UnlockResult.UNSUPPORTED, it.message.orEmpty())
                }
            }
            publish(signal, result)
        }, "izuki-gesture-record").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun poll(): String {
        val snapshot = synchronized(resultLock) {
            val current = resultJson
            if (current != IDLE_JSON && current != RECORDING_JSON) {
                resultJson = IDLE_JSON
            }
            current
        }
        return snapshot
    }

    fun cancel() {
        PrivilegedLog.i("GestureRecorder: cancel requested")
        cancelSignal.countDown()
    }

    private fun publish(signal: CountDownLatch, result: String) {
        synchronized(resultLock) {
            if (signal !== cancelSignal) {
                PrivilegedLog.i("GestureRecorder: superseded run finished, result dropped")
                return
            }
            resultJson = if (signal.isCancelled()) IDLE_JSON else result
        }
    }

    private fun record(deadline: Long, signal: CountDownLatch): String {
        val device = findTouchDevice()
            ?: return encodeFailed(UnlockResult.UNSUPPORTED, "no readable touch device")
        val reader = InputEventReader.open(device.path)
            ?: return encodeFailed(UnlockResult.UNSUPPORTED, "cannot open ${device.path}")

        val parser = TouchStreamParser()
        val collecting = AtomicBoolean(false)
        val startedAt = AtomicLong(0L)
        val rawEvents = AtomicLong(0L)
        val collectedEvents = AtomicLong(0L)

        val readerThread = Thread({
            runCatching {
                reader.readLoop { type, code, value, atMs ->
                    rawEvents.incrementAndGet()
                    if (collecting.get()) {
                        collectedEvents.incrementAndGet()
                        val tMs = (atMs - startedAt.get()).toInt()
                        synchronized(parser) { parser.onEvent(type, code, value, tMs) }
                    }
                }
            }.onFailure {
                PrivilegedLog.i("GestureRecorder: reader stopped: ${it.message}")
            }
        }, "izuki-gesture-reader").apply {
            isDaemon = true
            start()
        }

        val outcome = try {
            lockThenAwaitUnlock(deadline, signal, collecting, startedAt)
        } finally {
            collecting.set(false)
            reader.close()
            readerThread.join(READER_JOIN_MS)
        }

        val screen = outcome.screen
        if (outcome.failure != null || screen == null) {
            return encodeFailed(
                outcome.failure ?: UnlockResult.UNSUPPORTED,
                "device=${device.path} raw=${rawEvents.get()} collected=${collectedEvents.get()}",
            )
        }

        val elapsed = (SystemClock.uptimeMillis() - startedAt.get()).toInt().coerceAtLeast(1)
        val strokes = synchronized(parser) {
            parser.finish(elapsed)
            parser.strokes.toList()
        }
        val mapped = TouchCoordMapper.mapStrokes(strokes, device, screen)
        val pattern = UnlockGestureCodec.build(mapped, screen)
        val detail = "device=${device.path} ${device.displayName} raw=${rawEvents.get()} " +
            "collected=${collectedEvents.get()} strokes=${pattern.strokes.size}"
        PrivilegedLog.i("GestureRecorder: captured $detail on ${screen.width}x${screen.height} rotation=${screen.rotation}")

        if (pattern.strokes.isEmpty()) {
            return encodeFailed(UnlockResult.NO_INPUT, detail)
        }
        return encodeDone(pattern, detail)
    }

    private fun lockThenAwaitUnlock(
        deadline: Long,
        signal: CountDownLatch,
        collecting: AtomicBoolean,
        startedAt: AtomicLong,
    ): Outcome {
        if (signal.isCancelled()) return Outcome(UnlockResult.CANCELLED)

        val locked = controller.lockAndSleep()
        if (signal.isCancelled()) return Outcome(UnlockResult.CANCELLED)
        if (locked != UnlockResult.OK) {
            PrivilegedLog.w("GestureRecorder: cannot lock for recording: $locked")
            return Outcome(locked)
        }

        if (!pollUntil(SCREEN_OFF_WAIT_MS) { !controller.isScreenOn() }) {
            PrivilegedLog.w("GestureRecorder: screen still on after lock, recording anyway")
        }
        if (signal.isCancelled()) return Outcome(UnlockResult.CANCELLED)

        if (!controller.wakeScreen()) {
            PrivilegedLog.w("GestureRecorder: screen did not turn on, gesture would be useless")
            return Outcome(UnlockResult.WAKE_FAILED)
        }

        val screen = HiddenDisplayManager.current()
            ?: return Outcome(UnlockResult.UNSUPPORTED)

        // 必须紧接着开始采集，中间不留缓冲，避免漏掉用户的第一下。
        startedAt.set(SystemClock.uptimeMillis())
        collecting.set(true)
        PrivilegedLog.i("GestureRecorder: collecting on ${screen.width}x${screen.height} rotation=${screen.rotation}")

        // 唤醒瞬间 keyguard 会抖，先让状态稳下来，再等它消失。
        if (sleepUnlessCancelled(signal, KEYGUARD_SETTLE_MS)) {
            return Outcome(UnlockResult.CANCELLED)
        }
        val keyguardLocked = controller.isKeyguardLocked()
        if (keyguardLocked == null) return Outcome(UnlockResult.UNSUPPORTED)
        if (!keyguardLocked) {
            PrivilegedLog.w("GestureRecorder: keyguard already gone right after wake")
            return Outcome(UnlockResult.WAKE_FAILED)
        }

        val unlocked = awaitCancellable(deadline, signal) {
            controller.isKeyguardLocked() == false
        }
        if (unlocked != UnlockResult.OK) {
            PrivilegedLog.w("GestureRecorder: keyguard still locked: $unlocked")
            return Outcome(unlocked)
        }

        Thread.sleep(TAIL_AFTER_UNLOCK_MS)
        return Outcome(failure = null, screen = screen)
    }

    private fun findTouchDevice(): com.benton.izukijs.service.TouchInputDevice? {
        val output = runGetevent()
        if (output.isNullOrBlank()) {
            PrivilegedLog.w("GestureRecorder: getevent -lp produced nothing")
            return null
        }
        val candidates = UnlockRecordingParser.parseTouchDevices(output)
        if (candidates.isEmpty()) {
            PrivilegedLog.w("GestureRecorder: no touch candidates in getevent output")
            return null
        }
        val readable = candidates.firstOrNull { File(it.path).canRead() }
        if (readable == null) {
            PrivilegedLog.w("GestureRecorder: no readable candidates: ${candidates.joinToString { it.path }}")
            return null
        }
        PrivilegedLog.i(
            "GestureRecorder: using ${readable.displayName} (${readable.path}, " +
                "x=${readable.minX}..${readable.maxX}, y=${readable.minY}..${readable.maxY})",
        )
        return readable
    }

    private fun runGetevent(): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getevent", "-lp")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.onFailure {
        PrivilegedLog.w("GestureRecorder: getevent failed: ${it.message}")
    }.getOrNull()

    private fun awaitCancellable(
        deadline: Long,
        signal: CountDownLatch,
        condition: () -> Boolean,
    ): UnlockResult {
        while (true) {
            if (signal.isCancelled()) return UnlockResult.CANCELLED
            if (condition()) return UnlockResult.OK
            if (SystemClock.elapsedRealtime() >= deadline) return UnlockResult.RECORDING_TIMEOUT
            if (sleepUnlessCancelled(signal, KEYGUARD_POLL_MS)) return UnlockResult.CANCELLED
        }
    }

    private fun sleepUnlessCancelled(signal: CountDownLatch, durationMs: Long): Boolean =
        signal.await(durationMs, TimeUnit.MILLISECONDS)

    private fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    @SuppressLint("WakelockTimeout")
    private inline fun <T> keepCpuAwake(timeoutMs: Long, block: () -> T): T {
        val lock = runCatching {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(timeoutMs)
            }
        }.getOrNull()
        return try {
            block()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun CountDownLatch.isCancelled(): Boolean = count == 0L

    private class Outcome(val failure: UnlockResult?, val screen: ScreenGeometry? = null)

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 90_000L
        const val SCREEN_OFF_WAIT_MS = 2_000L
        const val TAIL_AFTER_UNLOCK_MS = 200L
        const val KEYGUARD_SETTLE_MS = 600L
        const val KEYGUARD_POLL_MS = 250L
        const val READER_JOIN_MS = 1_000L
        const val PREVIOUS_JOIN_MS = 15_000L
        const val CPU_LOCK_EXTRA_MS = 30_000L
        const val POLL_INTERVAL_MS = 100L
        const val CPU_LOCK_TAG = "Izuki:gesture-record"

        val IDLE_JSON = """{"status":"IDLE"}"""
        val RECORDING_JSON = """{"status":"RECORDING"}"""

        fun encodeDone(pattern: UnlockPattern, detail: String): String = JSONObject().apply {
            put("status", "DONE")
            put("pattern", pattern.toJson())
            put("detail", detail)
        }.toString()

        fun encodeFailed(error: UnlockResult, detail: String): String = JSONObject().apply {
            put("status", "FAILED")
            put("error", error.name)
            put("detail", detail)
        }.toString()
    }
}
