package com.benton.izukijs.service

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.controller.GesturePoint
import com.benton.izukijs.controller.GestureStroke
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.data.UnlockSettings
import com.benton.izukijs.data.UnlockSettingsRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.PatternPoint
import com.benton.izukijs.model.UnlockMethod
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockStroke
import com.benton.izukijs.privileged.PrivilegedUnlockClient
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** 自动解锁结果；不含任何凭据内容。 */
enum class UnlockResult {
    OK,
    ALREADY_UNLOCKED,
    DISABLED,
    NO_BACKEND,
    LOCK_FAILED,
    WAKE_FAILED,
    DISMISS_FAILED,
    CREDENTIAL_REQUIRED,
    CREDENTIAL_INVALID,
    CREDENTIAL_REJECTED,
    PATTERN_SCREEN_MISMATCH,
    UNSUPPORTED,
    TIMEOUT,
    NO_INPUT,
    RECORDING_TIMEOUT,
    CANCELLED,
}

/**
 * 锁屏自主解锁。
 *
 * 优先走 Shizuku UserService（特权进程内直读 /dev/input + InputManager.injectInputEvent）；
 * Shizuku 不可用或特权服务返回 UNSUPPORTED 时，回退下面这套 Shell 实现：唤醒、收起锁屏、
 * 注入 PIN / 图案，最后用 [KeyguardManager] 校验结果。PIN 与图案均不会写入日志。
 */
class ScreenUnlockController(
    private val context: Context,
    private val controllers: ControllerManager,
    private val settingsRepository: UnlockSettingsRepository,
    private val wakeLock: WakeLockController,
    private val privileged: PrivilegedUnlockClient,
    private val logBus: LogBus,
) {

    fun isEnabled(): Boolean = settingsRepository.current().enabled

    suspend fun ensureUnlocked(): UnlockResult {
        val settings = settingsRepository.current()
        if (!settings.enabled) return UnlockResult.DISABLED

        val privilegedResult = privileged.unlock(settings)
        if (privilegedResult != null && privilegedResult != UnlockResult.UNSUPPORTED) {
            logBus.info("自动解锁（特权服务）: $privilegedResult")
            return privilegedResult
        }
        if (privilegedResult == UnlockResult.UNSUPPORTED) {
            logBus.warn("自动解锁：特权服务不支持当前设备，回退 Shell")
        } else {
            logBus.info("自动解锁：特权服务不可用，回退 Shell")
        }
        return withContext(Dispatchers.IO) { unlock(settings) }
    }

    /**
     * 供设置页「测试解锁」调用：先主动锁屏，再走完整解锁链路。不检查总开关，但仍要求
     * 已配置凭据；缺少凭据时不会锁屏，避免把设备锁在一个无法自动解锁的状态。
     */
    suspend fun testUnlock(): UnlockResult {
        val settings = settingsRepository.current()
        if (!settings.hasCredential) return UnlockResult.CREDENTIAL_REQUIRED
        if (settings.method == UnlockMethod.SWIPE && isDeviceSecure()) {
            return UnlockResult.CREDENTIAL_REQUIRED
        }

        val privilegedResult = privileged.testUnlock(settings)
        if (privilegedResult != null && privilegedResult != UnlockResult.UNSUPPORTED) {
            logBus.info("测试解锁（特权服务）: $privilegedResult")
            return privilegedResult
        }
        if (privilegedResult == UnlockResult.UNSUPPORTED) {
            logBus.warn("测试解锁：特权服务不支持当前设备，回退 Shell")
        } else {
            logBus.info("测试解锁：特权服务不可用，回退 Shell")
        }
        return withContext(Dispatchers.IO) { testUnlockShell(settings) }
    }

    private fun testUnlockShell(settings: UnlockSettings): UnlockResult {
        val controller = controllers.controllerFor(Capability.SHELL) ?: return UnlockResult.NO_BACKEND
        if (!isLocked()) {
            controller.pressKey(KeyEvent.KEYCODE_SLEEP)
            if (!await(TEST_LOCK_TIMEOUT_MS) { isLocked() || !isInteractive() }) {
                controller.pressKey(KeyEvent.KEYCODE_POWER)
                if (!await(TEST_LOCK_TIMEOUT_MS) { isLocked() || !isInteractive() }) {
                    return UnlockResult.LOCK_FAILED
                }
            }
            Thread.sleep(TEST_LOCK_SETTLE_MS)
        }
        if (!isInteractive()) {
            controller.pressKey(KeyEvent.KEYCODE_WAKEUP)
            awaitInteractive()
        }
        return unlock(settings)
    }

    private fun unlock(settings: UnlockSettings): UnlockResult {
        val controller = controllers.controllerFor(Capability.SHELL) ?: return UnlockResult.NO_BACKEND
        wakeLock.acquire()
        try {
            // 必须先亮屏再判断锁屏状态：灭屏时 KeyguardManager 可能暂时报 false，
            // 提前返回 ALREADY_UNLOCKED 会让脚本在锁屏上运行。
            if (!isInteractive()) {
                controller.pressKey(KeyEvent.KEYCODE_WAKEUP)
                if (!awaitInteractive()) return UnlockResult.WAKE_FAILED
            }
            if (!isLocked()) return UnlockResult.ALREADY_UNLOCKED

            val secure = isDeviceSecure()
            if (!secure) {
                dismissKeyguard(controller)
                Thread.sleep(BOUNCER_SETTLE_MS)
                if (!isLocked()) return UnlockResult.OK
                val size = screenInfo()
                controller.swipe(
                    size.width / 2f,
                    size.height * 0.82f,
                    size.width / 2f,
                    size.height * 0.18f,
                    260L,
                )
                return if (awaitUnlocked(DISMISS_TIMEOUT_MS)) {
                    UnlockResult.OK
                } else {
                    UnlockResult.DISMISS_FAILED
                }
            }

            return when (settings.method) {
                UnlockMethod.SWIPE -> UnlockResult.CREDENTIAL_REQUIRED
                UnlockMethod.PIN -> {
                    if (dismissKeyguard(controller)) {
                        Thread.sleep(BOUNCER_SETTLE_MS)
                    }
                    if (!isLocked()) return UnlockResult.OK
                    unlockWithPin(controller, settings)
                }

                // 图案录制的是「亮屏后的完整锁屏动作」，可能包含上滑和图案两段；
                // 这里不能再 dismiss-keyguard，否则会把 bouncer 提前打开，回放轨迹就对不上了。
                UnlockMethod.PATTERN -> unlockWithPattern(controller, settings)
            }
        } finally {
            wakeLock.release()
        }
    }

    private fun dismissKeyguard(controller: DeviceController): Boolean {
        val dismiss = controller.shell("wm dismiss-keyguard")
        if (dismiss != null && dismiss.exitCode == 0) return true
        // 部分 ROM 不提供 wm dismiss-keyguard；MENU 至少能把 bouncer 叫出来。
        controller.pressKey(KeyEvent.KEYCODE_MENU)
        return false
    }

    /**
     * 确保图案面板（primary bouncer）已经显示。返回 false 仅表示无法确认/触发，
     * 调用方仍可尝试注入，避免在无法读取 SystemUI 状态的 ROM 上直接放弃。
     */
    private fun ensureBouncer(controller: DeviceController): Boolean {
        when (bouncerVisible(controller)) {
            true -> return true
            false -> Unit
            null -> {
                // 读不到状态时保留旧行为：只 dismiss 一次并等待动画结束。
                dismissKeyguard(controller)
                Thread.sleep(BOUNCER_SETTLE_MS)
                return true
            }
        }

        val dismissed = dismissKeyguard(controller)
        val deadline = SystemClock.elapsedRealtime() + BOUNCER_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (bouncerVisible(controller) == true) return true
            Thread.sleep(BOUNCER_POLL_MS)
        }
        if (dismissed) Thread.sleep(BOUNCER_SETTLE_MS)
        return bouncerVisible(controller) == true || dismissed
    }

    /** `dumpsys activity service` 的 SystemUI 转储里能看到 mBouncerVisible。 */
    private fun bouncerVisible(controller: DeviceController): Boolean? {
        val output = controller.shell(
            "dumpsys activity service com.android.systemui/.SystemUIService | " +
                "grep -m1 mBouncerVisible",
        )?.stdout ?: return null
        if (!output.contains("mBouncerVisible=")) return null
        return output.contains("mBouncerVisible=true")
    }

    private fun unlockWithPin(controller: DeviceController, settings: UnlockSettings): UnlockResult {
        val pin = settings.pin()
        if (pin.isEmpty() || pin.any { !it.isDigit() }) return UnlockResult.CREDENTIAL_REQUIRED
        for (digit in pin) {
            if (!isLocked()) return UnlockResult.OK
            if (!controller.pressKey(KeyEvent.KEYCODE_0 + (digit - '0'))) {
                return UnlockResult.CREDENTIAL_REJECTED
            }
            Thread.sleep(PIN_DIGIT_GAP_MS)
        }
        if (settings.enterAfterPin) controller.pressKey(KeyEvent.KEYCODE_ENTER)
        return if (awaitUnlocked(UNLOCK_TIMEOUT_MS)) {
            UnlockResult.OK
        } else {
            logBus.warn("自动解锁：PIN 未通过或锁屏未及时消失")
            UnlockResult.CREDENTIAL_REJECTED
        }
    }

    private fun unlockWithPattern(controller: DeviceController, settings: UnlockSettings): UnlockResult {
        val pattern = settings.pattern() ?: return UnlockResult.CREDENTIAL_REQUIRED
        val screen = screenInfo()
        if (pattern.rotation != screen.rotation) {
            logBus.warn("自动解锁：图案录制方向与当前屏幕方向不一致")
            return UnlockResult.PATTERN_SCREEN_MISMATCH
        }
        if (!isLocked()) return UnlockResult.ALREADY_UNLOCKED

        val strokes = patternToGestureStrokes(pattern, screen.width, screen.height)
        if (strokes.isEmpty()) return UnlockResult.CREDENTIAL_REQUIRED

        // 旧 Shell 录制可能只录到图案本身，回放前必须先把 bouncer 打开；
        // 新的特权录制从亮屏首屏开始录完整动作，不能再打开 bouncer，否则轨迹会错位。
        if (pattern.screenWidth <= 0 && !ensureBouncer(controller)) {
            logBus.warn("自动解锁：未能打开图案面板，仍尝试注入")
        }

        // 唤醒后锁屏界面还在做窗口切换动画，过早注入会被 keyguard 丢掉。
        Thread.sleep(PATTERN_SETTLE_MS)
        if (!isLocked()) return UnlockResult.ALREADY_UNLOCKED

        val accessibility = controllers.all()
            .firstOrNull { it is AccessibilityController && it.isReady() } as? AccessibilityController
        if (accessibility != null &&
            replayViaAccessibility(accessibility, strokes, pattern.strokes)
        ) {
            Thread.sleep(PATTERN_SETTLE_MS)
            if (awaitUnlocked(ACCESSIBILITY_VERIFY_MS)) return UnlockResult.OK
            logBus.info("自动解锁：无障碍手势未解锁，改用特权触摸注入")
        }
        if (!isLocked()) return UnlockResult.ALREADY_UNLOCKED

        val streamSent = replayViaEvdevStream(controller, pattern)
        if (streamSent != null) {
            if (!streamSent) {
                logBus.warn("自动解锁：evdev 事件流注入失败")
                return UnlockResult.UNSUPPORTED
            }
            Thread.sleep(PATTERN_SETTLE_MS)
            if (awaitUnlocked(UNLOCK_TIMEOUT_MS)) return UnlockResult.OK
            logBus.warn("自动解锁：evdev 触摸注入未通过，建议改用 PIN")
            return UnlockResult.CREDENTIAL_REJECTED
        }

        val sendEventSent = replayViaSendevent(controller, pattern)
        if (sendEventSent != null) {
            if (!sendEventSent) {
                logBus.warn("自动解锁：sendevent 注入失败")
                return UnlockResult.UNSUPPORTED
            }
            Thread.sleep(PATTERN_SETTLE_MS)
            if (awaitUnlocked(UNLOCK_TIMEOUT_MS)) return UnlockResult.OK
            logBus.warn("自动解锁：特权触摸注入未通过，建议改用 PIN")
            return UnlockResult.CREDENTIAL_REJECTED
        }

        // 最后的 shell 兜底：部分 ROM 的 input motionevent 仍可用。
        if (!replayViaShell(controller, strokes, pattern.strokes)) {
            return UnlockResult.UNSUPPORTED
        }
        Thread.sleep(PATTERN_SETTLE_MS)
        return if (awaitUnlocked(UNLOCK_TIMEOUT_MS)) {
            UnlockResult.OK
        } else {
            logBus.warn("自动解锁：图案未通过，建议改用 PIN")
            UnlockResult.CREDENTIAL_REJECTED
        }
    }

    private fun replayViaAccessibility(
        accessibility: AccessibilityController,
        strokes: List<List<GesturePoint>>,
        recorded: List<UnlockStroke>,
    ): Boolean {
        for ((index, points) in strokes.withIndex()) {
            if (points.isEmpty()) continue
            val ok = if (points.size == 1) {
                accessibility.click(points[0].x, points[0].y)
            } else {
                accessibility.gesture(listOf(GestureStroke(points)))
            }
            if (!ok) return false
            val duration = recorded.getOrNull(index)?.durationMs ?: 0L
            Thread.sleep((duration + STROKE_GAP_MS).coerceAtLeast(STROKE_GAP_MS))
        }
        return true
    }

    /**
     * 图案回放降级：无障碍手势不可用时，用 Shell 的 `input motionevent` / `input tap`
     * 逐段注入。部分 ROM 不支持该命令时会由后续锁屏校验判定为失败。
     */
    private fun replayViaShell(
        controller: DeviceController,
        strokes: List<List<GesturePoint>>,
        recorded: List<UnlockStroke>,
    ): Boolean {
        if (strokes.isEmpty()) return false
        val command = buildString {
            strokes.forEachIndexed { strokeIndex, points ->
                if (points.isEmpty()) return@forEachIndexed
                if (points.size == 1) {
                    append("input tap ${points[0].x.toInt()} ${points[0].y.toInt()}")
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
                    points.forEachIndexed { index, point ->
                        val action = when (index) {
                            0 -> "DOWN"
                            points.lastIndex -> "UP"
                            else -> "MOVE"
                        }
                        append("input motionevent $action ${point.x.toInt()} ${point.y.toInt()}")
                        if (index < points.lastIndex) append("; sleep 0.02; ")
                    }
                }
                val duration = recorded.getOrNull(strokeIndex)?.durationMs ?: 0L
                if (strokeIndex < strokes.lastIndex) {
                    append("; sleep ${((duration + STROKE_GAP_MS) / 1000f).coerceAtLeast(0.15f)}; ")
                }
            }
        }
        return controller.shell(command)?.exitCode == 0
    }

    private fun preferredShellController(fallback: DeviceController): DeviceController =
        controllers.all().firstOrNull {
            it.mode == ControlMode.ROOT && it.isReady() && !controllers.isDisabled(it.mode)
        } ?: fallback

    /**
     * 把整段手势预先编码成 evdev 二进制流，一次 `base64 -d > /dev/input/eventN` 写入。
     *
     * 这比逐事件调用 `sendevent` 快一个数量级：一台 720p 设备上 64 个点的图案，
     * `sendevent` 要 10 秒以上，而一次写入通常 < 1 秒；图案锁不会因为中途停顿而断线。
     *
     * @return null 表示环境不支持（没有 root 写权限 / 没有 base64）；true/false 表示已执行。
     */
    private fun replayViaEvdevStream(
        controller: DeviceController,
        pattern: UnlockPattern,
    ): Boolean? {
        val executor = preferredShellController(controller)
        if (executor.shell("test -x /system/bin/base64")?.exitCode != 0) return null
        val output = executor.shell("getevent -lp")?.stdout ?: return null
        val candidates = UnlockRecordingParser.parseTouchDevices(output)
        if (candidates.isEmpty()) return null
        val device = candidates.firstOrNull {
            executor.shell("test -w '${it.path}'")?.exitCode == 0
        } ?: return null
        val stream = buildEvdevStream(device, pattern)
        if (stream.bytes.isEmpty()) return null
        val encoded = Base64.encodeToString(stream.bytes, Base64.NO_WRAP)
        if (encoded.isEmpty()) return null

        logBus.info("自动解锁：使用 ${device.displayName} (${device.path}) 写入 evdev 事件流")
        // 不能把整段流一次性写进 evdev：输入系统会按同一时间戳把中间点合并掉。
        // 先解码到普通文件，再按帧用 dd 写入，并用 sleep 让锁屏逐帧收到移动。
        val temp = "/data/local/tmp/izuki_unlock_${Process.myPid()}.bin"
        val command = StringBuilder("set -e\n")
        command.append("echo '").append(encoded).append("' | base64 -d > '")
            .append(temp).append("'\n")
        var skip = 0
        stream.frameCounts.forEachIndexed { index, count ->
            command.append("dd if='").append(temp)
                .append("' of='").append(device.path)
                .append("' bs=").append(stream.eventSize)
                .append(" skip=").append(skip)
                .append(" count=").append(count)
                .append(" 2>/dev/null\n")
            if (index < stream.frameCounts.lastIndex) command.append("sleep 0.012\n")
            skip += count
        }
        command.append("rm -f '").append(temp).append("'\n")

        val started = SystemClock.elapsedRealtime()
        val result = executor.shell(command.toString())
        val elapsed = SystemClock.elapsedRealtime() - started
        if (result == null || result.exitCode != 0) {
            logBus.info(
                "自动解锁：evdev 事件流不可写（${elapsed}ms, exit=${result?.exitCode}, " +
                    result?.stderr?.take(120).orEmpty() + "），回退 sendevent",
            )
            return null
        }
        logBus.info(
            "自动解锁：evdev 事件流写入完成（${elapsed}ms，${stream.bytes.size} 字节，" +
                "${stream.frameCounts.size} 帧，${pattern.strokes.size} 段）",
        )
        return true
    }

    private class EvdevStream(
        val bytes: ByteArray,
        val eventSize: Int,
        val frameCounts: List<Int>,
    )

    /**
     * `struct input_event` 流。64 位进程是 24 字节（timeval 16 + type 2 + code 2 + value 4），
     * 32 位进程是 16 字节（timeval 8 + type 2 + code 2 + value 4）。
     */
    private fun buildEvdevStream(
        device: TouchInputDevice,
        pattern: UnlockPattern,
    ): EvdevStream {
        val frames = mutableListOf<MutableList<Triple<Int, Int, Int>>>()
        var frame = mutableListOf<Triple<Int, Int, Int>>()
        fun add(type: Int, code: Int, value: Int) = frame.add(Triple(type, code, value))
        fun sync() {
            add(EV_SYN, SYN_REPORT, 0)
            frames += frame
            frame = mutableListOf()
        }

        fun rawX(x: Float): Int =
            Math.round(device.minX + x * device.rangeX).coerceIn(device.minX, device.maxX)

        fun rawY(y: Float): Int =
            Math.round(device.minY + y * device.rangeY).coerceIn(device.minY, device.maxY)

        fun addPoint(x: Float, y: Float) {
            if (device.protocolB) {
                add(EV_ABS, ABS_MT_POSITION_X, rawX(x))
                add(EV_ABS, ABS_MT_POSITION_Y, rawY(y))
            } else {
                add(EV_ABS, ABS_X, rawX(x))
                add(EV_ABS, ABS_Y, rawY(y))
                if (device.hasMtReport) add(EV_SYN, SYN_MT_REPORT, 0)
            }
            // InputReader 需要非零压力/触摸面积才会把 MT 指针当成真正按下。
            if (device.hasTouchMajor) add(EV_ABS, ABS_MT_TOUCH_MAJOR, 1)
            if (device.hasPressure) {
                add(EV_ABS, device.pressureCode, device.pressureMax.coerceAtLeast(1))
            }
        }

        val trackingId = ((SystemClock.uptimeMillis() % 60_000L) + 1L).toInt()
        pattern.strokes.forEachIndexed { index, stroke ->
            if (stroke.points.isEmpty()) return@forEachIndexed
            val points = simplifyStroke(stroke.points)
            if (device.protocolB) {
                add(EV_ABS, ABS_MT_SLOT, 0)
                add(EV_ABS, ABS_MT_TRACKING_ID, trackingId + index)
            }
            if (device.hasBtnTouch) add(EV_KEY, BTN_TOUCH, 1)
            addPoint(points[0].x, points[0].y)
            sync()
            for (i in 1 until points.size) {
                addPoint(points[i].x, points[i].y)
                sync()
            }
            if (device.protocolB) add(EV_ABS, ABS_MT_TRACKING_ID, -1)
            if (device.hasBtnTouch) add(EV_KEY, BTN_TOUCH, 0)
            sync()
        }

        if (frames.isEmpty()) return EvdevStream(ByteArray(0), 24, emptyList())
        val is64 = Process.is64Bit()
        val eventSize = if (is64) 24 else 16
        val now = SystemClock.uptimeMillis()
        val firstMs = (now - (frames.size - 1).coerceAtLeast(0) * FRAME_INTERVAL_MS)
            .coerceAtLeast(0L)
        val totalEvents = frames.sumOf { it.size }
        val buffer = ByteBuffer.allocate(totalEvents * eventSize).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEachIndexed { frameIndex, events ->
            val time = firstMs + frameIndex * FRAME_INTERVAL_MS
            val seconds = time / 1000L
            val micros = ((time % 1000L) * 1000L).toInt()
            events.forEach { (type, code, value) ->
                if (is64) {
                    buffer.putLong(seconds)
                    buffer.putLong(micros.toLong())
                } else {
                    buffer.putInt(seconds.toInt())
                    buffer.putInt(micros)
                }
                buffer.putShort(type.toShort())
                buffer.putShort(code.toShort())
                buffer.putInt(value)
            }
        }
        return EvdevStream(buffer.array(), eventSize, frames.map { it.size })
    }

    /**
     * 直接向触摸设备写 evdev 事件（需要 Root/Shizuku shell 对 /dev/input/eventN 有写权限）。
     *
     * `input motionevent` 每次调用都是独立进程，downTime 不连续，图案锁通常会把它当成
     * 多段手势或取消；这里把整段轨迹放在同一个 shell 里连续写入，系统看到的是一次完整
     * 的按下 → 移动 → 抬起。
     *
     * @return null 表示当前环境不支持 sendevent（调用方回退旧路径）；true/false 表示已执行。
     */
    private fun replayViaSendevent(
        controller: DeviceController,
        pattern: UnlockPattern,
    ): Boolean? {
        // sendevent 每个事件都是独立进程，走 Shizuku 的 newProcess 会非常慢；
        // 有 Root 时优先让 su 把一个脚本跑完，能明显缩短回放耗时。
        val executor = preferredShellController(controller)
        if (executor.shell("test -x /system/bin/sendevent")?.exitCode != 0) return null
        val output = executor.shell("getevent -lp")?.stdout ?: return null
        val candidates = UnlockRecordingParser.parseTouchDevices(output)
        if (candidates.isEmpty()) return null
        val device = candidates.firstOrNull {
            executor.shell("test -w '${it.path}'")?.exitCode == 0
        } ?: return null

        // test -w 只检查 DAC，SELinux 仍可能拒绝；用一条空 SYN 做真实写探针。
        val probe = executor.shell("sendevent '${device.path}' 0 0 0")
        if (probe == null || probe.exitCode != 0) {
            logBus.info("自动解锁：sendevent 不可写，回退 input 注入")
            return null
        }

        logBus.info("自动解锁：使用 ${device.displayName} (${device.path}) 注入触摸轨迹")
        val script = StringBuilder("set -e\n")
        fun send(type: Int, code: Int, value: Int) {
            script.append("sendevent '").append(device.path).append("' ")
                .append(type).append(' ').append(code).append(' ').append(value).append('\n')
        }

        fun sync() = send(EV_SYN, SYN_REPORT, 0)

        fun sleepMs(ms: Long) {
            if (ms <= 0) return
            script.append("sleep ").append(String.format(Locale.US, "%.3f", ms / 1000.0)).append('\n')
        }

        fun rawX(x: Float): Int =
            Math.round(device.minX + x * device.rangeX).coerceIn(device.minX, device.maxX)

        fun rawY(y: Float): Int =
            Math.round(device.minY + y * device.rangeY).coerceIn(device.minY, device.maxY)

        fun sendPoint(x: Float, y: Float, down: Boolean) {
            if (device.protocolB) {
                send(EV_ABS, ABS_MT_POSITION_X, rawX(x))
                send(EV_ABS, ABS_MT_POSITION_Y, rawY(y))
            } else {
                send(EV_ABS, ABS_X, rawX(x))
                send(EV_ABS, ABS_Y, rawY(y))
                if (device.hasMtReport) send(EV_SYN, SYN_MT_REPORT, 0)
            }
            if (device.hasTouchMajor) send(EV_ABS, ABS_MT_TOUCH_MAJOR, 1)
            if (device.hasPressure) {
                send(EV_ABS, device.pressureCode, device.pressureMax.coerceAtLeast(1))
            }
            if (down && device.hasBtnTouch) send(EV_KEY, BTN_TOUCH, 1)
            sync()
        }

        val trackingId = ((SystemClock.uptimeMillis() % 60_000L) + 1L).toInt()
        var inputPoints = 0
        var sentPoints = 0
        pattern.strokes.forEachIndexed { index, stroke ->
            if (stroke.points.isEmpty()) return@forEachIndexed
            val points = simplifyStroke(stroke.points)
            inputPoints += stroke.points.size
            sentPoints += points.size
            if (index > 0) sleepMs(INTER_STROKE_GAP_MS)
            if (device.protocolB) {
                send(EV_ABS, ABS_MT_SLOT, 0)
                send(EV_ABS, ABS_MT_TRACKING_ID, trackingId + index)
            }
            sendPoint(points[0].x, points[0].y, down = true)

            if (points.size == 1) {
                sleepMs(TAP_HOLD_MS)
            } else {
                for (i in 1 until points.size) {
                    sendPoint(points[i].x, points[i].y, down = false)
                }
            }

            if (device.protocolB) send(EV_ABS, ABS_MT_TRACKING_ID, -1)
            if (device.hasBtnTouch) send(EV_KEY, BTN_TOUCH, 0)
            sync()
        }

        val started = SystemClock.elapsedRealtime()
        val result = executor.shell(script.toString())
        val elapsed = SystemClock.elapsedRealtime() - started
        if (result == null || result.exitCode != 0) {
            logBus.warn(
                "自动解锁：sendevent 注入失败（${elapsed}ms, exit=${result?.exitCode}, " +
                    result?.stderr?.take(120).orEmpty() + "）",
            )
            return false
        }
        logBus.info(
            "自动解锁：sendevent 注入完成（${elapsed}ms，${pattern.strokes.size} 段，" +
                "$inputPoints 点压缩为 $sentPoints 点）",
        )
        return true
    }

    /** 图案只需要足够密的采样点；把每个 SYN 帧都发一遍会让 sendevent 进程数爆炸。 */
    private fun simplifyStroke(points: List<PatternPoint>): List<PatternPoint> {
        if (points.size <= 2) return points
        val simplified = mutableListOf(points.first())
        var last = points.first()
        for (i in 1 until points.lastIndex) {
            val point = points[i]
            val dx = point.x - last.x
            val dy = point.y - last.y
            if (dx * dx + dy * dy >= MIN_POINT_DISTANCE_SQ) {
                simplified += point
                last = point
            }
        }
        if (simplified.last() != points.last()) simplified += points.last()
        if (simplified.size <= MAX_POINTS_PER_STROKE) return simplified
        return List(MAX_POINTS_PER_STROKE) { i ->
            simplified[i * (simplified.size - 1) / (MAX_POINTS_PER_STROKE - 1)]
        }
    }

    fun isLocked(): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    private fun isDeviceSecure(): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: false
    }.getOrDefault(false)

    fun isInteractive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    private fun awaitInteractive(timeoutMs: Long = WAKE_TIMEOUT_MS): Boolean =
        await(timeoutMs) { isInteractive() }

    private fun awaitUnlocked(timeoutMs: Long = UNLOCK_TIMEOUT_MS): Boolean =
        await(timeoutMs) { !isLocked() }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun screenInfo(): ScreenInfo {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return ScreenInfo(
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
            rotation = display?.rotation ?: 0,
        )
    }

    private data class ScreenInfo(val width: Int, val height: Int, val rotation: Int)

    private companion object {
        const val WAKE_TIMEOUT_MS = 3_000L
        const val DISMISS_TIMEOUT_MS = 3_000L
        const val TEST_LOCK_TIMEOUT_MS = 4_000L
        const val TEST_LOCK_SETTLE_MS = 500L
        const val UNLOCK_TIMEOUT_MS = 5_000L
        const val ACCESSIBILITY_VERIFY_MS = 1_500L
        const val BOUNCER_SETTLE_MS = 1_200L
        const val BOUNCER_WAIT_MS = 2_000L
        const val BOUNCER_POLL_MS = 150L
        const val PIN_DIGIT_GAP_MS = 50L
        const val PATTERN_SETTLE_MS = 800L
        const val STROKE_GAP_MS = 180L
        const val TAP_HOLD_MS = 60L
        const val INTER_STROKE_GAP_MS = 250L
        const val FRAME_INTERVAL_MS = 12L
        const val MIN_POINT_DISTANCE_SQ = 0.015f * 0.015f
        const val MAX_POINTS_PER_STROKE = 64
        const val POLL_INTERVAL_MS = 100L

        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val SYN_MT_REPORT = 2
        const val ABS_X = 0
        const val ABS_Y = 1
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_TOUCH_MAJOR = 0x30
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val BTN_TOUCH = 0x14a
    }
}

/** 把归一化录制轨迹映射到当前屏幕；每段笔画单独回放。 */
internal fun patternToGestureStrokes(
    pattern: UnlockPattern,
    width: Int,
    height: Int,
): List<List<GesturePoint>> = pattern.strokes.map { stroke ->
    stroke.points.mapIndexed { index, point ->
        GesturePoint(
            x = point.x * width,
            y = point.y * height,
            timeMs = point.timeMs.takeIf { it > 0L } ?: (index * PATTERN_POINT_INTERVAL_MS),
        )
    }
}

private const val PATTERN_POINT_INTERVAL_MS = 16L
