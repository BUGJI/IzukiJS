package com.benton.izukijs.service

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.DeviceController
import com.benton.izukijs.data.UnlockSettingsRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.TouchInputDevice
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockRecordingParser
import com.benton.izukijs.model.UnlockResult
import com.benton.izukijs.privileged.PrivilegedUnlockClient
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface UnlockRecordingState {
    data object Idle : UnlockRecordingState
    data object Preparing : UnlockRecordingState
    data object Recording : UnlockRecordingState
    data object Saving : UnlockRecordingState
    data class Success(val pattern: UnlockPattern) : UnlockRecordingState
    data class Failed(val result: UnlockResult) : UnlockRecordingState
}

/**
 * 在真实锁屏上录制解锁动作。
 *
 * 优先走 Shizuku UserService：特权进程里直读 /dev/input，亮屏后立刻开始采集，
 * 支持图案、连续手势与多次点击。Shizuku 不可用时回退旧的 Shell + getevent 路径。
 */
class UnlockGestureRecorder(
    private val context: Context,
    private val controllers: ControllerManager,
    private val settingsRepository: UnlockSettingsRepository,
    private val wakeLock: WakeLockController,
    private val privileged: PrivilegedUnlockClient,
    private val logBus: LogBus,
) {

    private val _state = MutableStateFlow<UnlockRecordingState>(UnlockRecordingState.Idle)
    val state: StateFlow<UnlockRecordingState> = _state.asStateFlow()

    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeController: DeviceController? = null

    @Volatile
    private var activeDevicePath: String? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        if (isBusy()) return@withContext
        cancelled = false
        _state.value = UnlockRecordingState.Preparing

        if (privileged.isShizukuAvailable() &&
            privileged.startGestureRecord(PRIVILEGED_RECORD_TIMEOUT_MS.toInt())
        ) {
            logBus.info("录制解锁动作：使用特权服务，锁屏后会自动亮屏")
            _state.value = UnlockRecordingState.Recording
            if (awaitPrivilegedRecord()) return@withContext
            logBus.warn("录制解锁动作：特权服务不支持当前设备，回退 Shell")
            privileged.cancelGestureRecord()
            delay(PRIVILEGED_FALLBACK_DELAY_MS)
        } else {
            logBus.info("录制解锁动作：特权服务不可用，回退 Shell")
        }

        _state.value = UnlockRecordingState.Preparing
        startShell()
    }

    /** @return true 表示结果已处理；false 表示特权服务不可用，调用方应回退 Shell。 */
    private suspend fun awaitPrivilegedRecord(): Boolean {
        val deadline = SystemClock.elapsedRealtime() +
            PRIVILEGED_RECORD_TIMEOUT_MS + PRIVILEGED_RECORD_GRACE_MS
        while (true) {
            if (cancelled) {
                privileged.cancelGestureRecord()
                _state.value = UnlockRecordingState.Failed(UnlockResult.CANCELLED)
                return true
            }
            val result = privileged.pollGestureRecord()
            if (result != null && result.isTerminal) {
                when (result.status) {
                    "DONE" -> {
                        val pattern = result.pattern
                        if (pattern == null || pattern.strokes.isEmpty()) {
                            _state.value = UnlockRecordingState.Failed(UnlockResult.NO_INPUT)
                            return true
                        }
                        _state.value = UnlockRecordingState.Saving
                        settingsRepository.setPattern(pattern)
                        logBus.info(
                            "录制解锁动作完成：${pattern.strokes.size} 段轨迹" +
                                (result.detail?.let { "（$it）" } ?: ""),
                        )
                        _state.value = UnlockRecordingState.Success(pattern)
                        return true
                    }

                    "FAILED" -> {
                        val error = result.error ?: UnlockResult.UNSUPPORTED
                        logBus.warn(
                            "录制解锁动作失败: $error" +
                                (result.detail?.let { "（$it）" } ?: ""),
                        )
                        if (error == UnlockResult.UNSUPPORTED) {
                            _state.value = UnlockRecordingState.Preparing
                            return false
                        }
                        _state.value = UnlockRecordingState.Failed(error)
                        return true
                    }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                privileged.cancelGestureRecord()
                _state.value = UnlockRecordingState.Failed(UnlockResult.RECORDING_TIMEOUT)
                return true
            }
            delay(PRIVILEGED_POLL_INTERVAL_MS)
        }
    }

    private suspend fun startShell() {
        val controller = controllers.controllerFor(Capability.SHELL)
        if (controller == null) {
            _state.value = UnlockRecordingState.Failed(UnlockResult.NO_BACKEND)
            return
        }
        activeController = controller

        wakeLock.acquire()
        try {
            val deviceInfo = findReadableTouchDevice(controller)
            if (deviceInfo == null) {
                _state.value = UnlockRecordingState.Failed(UnlockResult.UNSUPPORTED)
                return
            }
            activeDevicePath = deviceInfo.path
            logBus.info(
                "录制解锁动作：使用 ${deviceInfo.displayName} (${deviceInfo.path}, " +
                    "x=${deviceInfo.minX}..${deviceInfo.maxX}, y=${deviceInfo.minY}..${deviceInfo.maxY})",
            )

            if (!lockDevice(controller)) {
                logBus.warn("录制解锁动作：无法主动锁屏")
                _state.value = UnlockRecordingState.Failed(UnlockResult.LOCK_FAILED)
                return
            }
            if (!wakeAndWaitForKeyguard(controller)) {
                logBus.warn("录制解锁动作：亮屏后未检测到稳定锁屏")
                _state.value = UnlockRecordingState.Failed(UnlockResult.WAKE_FAILED)
                return
            }

            val rotation = screenRotation()
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + RECORD_TIMEOUT_MS
            _state.value = UnlockRecordingState.Recording
            logBus.info("录制解锁动作：开始采集，请完成解锁")

            val recordJob = recordScope.async {
                controller.shell(
                    "timeout $RECORD_TIMEOUT_SEC getevent -lt '${deviceInfo.path}'",
                )
            }
            val unlocked = awaitUnlocked(deadline)
            if (unlocked) {
                delay(TAIL_AFTER_UNLOCK_MS)
            }
            stopGetevent(controller, deviceInfo.path)
            val output = recordJob.await()?.stdout.orEmpty()
            _state.value = UnlockRecordingState.Saving

            val eventLineCount = output.lineSequence().count { it.isNotBlank() }
            val strokes = UnlockRecordingParser.parseStrokes(output, deviceInfo)
            logBus.info(
                "录制解锁动作：捕获输出 ${output.length} 字节 / $eventLineCount 行 / " +
                    "${strokes.size} 段轨迹（unlocked=$unlocked, cancelled=$cancelled）",
            )

            if (cancelled) {
                _state.value = UnlockRecordingState.Failed(UnlockResult.CANCELLED)
                return
            }

            if (strokes.isEmpty()) {
                _state.value = UnlockRecordingState.Failed(
                    if (unlocked) UnlockResult.NO_INPUT else UnlockResult.RECORDING_TIMEOUT,
                )
                return
            }
            if (!unlocked) {
                // 已经抓到轨迹但 keyguard 一直没消失：可能是错误图案，不要保存成凭据。
                logBus.warn("录制解锁动作：已捕获轨迹，但等待解锁超时，未保存")
                _state.value = UnlockRecordingState.Failed(UnlockResult.RECORDING_TIMEOUT)
                return
            }

            val pattern = UnlockPattern(
                rotation = rotation,
                strokes = strokes,
            )
            settingsRepository.setPattern(pattern)
            logBus.info("录制解锁动作完成：${strokes.size} 段触摸轨迹")
            _state.value = UnlockRecordingState.Success(pattern)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logBus.warn("录制解锁动作失败: ${t.message}")
            _state.value = UnlockRecordingState.Failed(UnlockResult.UNSUPPORTED)
        } finally {
            activeController?.let { controller ->
                activeDevicePath?.let { path -> stopGetevent(controller, path) }
            }
            activeDevicePath = null
            activeController = null
            wakeLock.release()
        }
    }

    fun cancel() {
        cancelled = true
        privileged.cancelGestureRecord()
        _state.value = UnlockRecordingState.Failed(UnlockResult.CANCELLED)
        activeController?.let { controller ->
            activeDevicePath?.let { path -> stopGetevent(controller, path) }
        }
    }

    fun resetState() {
        if (!isBusy()) {
            _state.value = UnlockRecordingState.Idle
        }
    }

    private fun isBusy(): Boolean =
        _state.value is UnlockRecordingState.Preparing ||
            _state.value is UnlockRecordingState.Recording ||
            _state.value is UnlockRecordingState.Saving

    private fun findReadableTouchDevice(controller: DeviceController): TouchInputDevice? {
        val output = controller.shell("getevent -lp")?.stdout
        if (output.isNullOrBlank()) {
            logBus.warn("录制解锁动作：getevent -lp 没有输出")
            return null
        }
        val candidates = UnlockRecordingParser.parseTouchDevices(output)
        if (candidates.isEmpty()) {
            logBus.warn("录制解锁动作：未找到可用的触摸设备（原始输出 ${output.length} 字节）")
            return null
        }
        candidates.forEach { candidate ->
            val readable = controller.shell("test -r '${candidate.path}'")?.exitCode == 0
            if (readable) return candidate
            logBus.debug("录制解锁动作：跳过不可读设备 ${candidate.path} ${candidate.displayName}")
        }
        logBus.warn(
            "录制解锁动作：找到 ${candidates.size} 个候选触摸设备，但没有一个可读：" +
                candidates.joinToString { it.path },
        )
        return null
    }

    private suspend fun lockDevice(controller: DeviceController): Boolean {
        if (isLocked()) return true
        controller.pressKey(KeyEvent.KEYCODE_SLEEP)
        if (await(TEST_LOCK_TIMEOUT_MS) { isLocked() || !isInteractive() }) return true
        controller.pressKey(KeyEvent.KEYCODE_POWER)
        return await(TEST_LOCK_TIMEOUT_MS) { isLocked() || !isInteractive() }
    }

    private suspend fun wakeAndWaitForKeyguard(controller: DeviceController): Boolean {
        if (!isInteractive()) {
            controller.pressKey(KeyEvent.KEYCODE_WAKEUP)
            if (!await(WAKE_TIMEOUT_MS) { isInteractive() }) return false
        }
        delay(KEYGUARD_SETTLE_MS)

        var stable = 0
        val deadline = SystemClock.elapsedRealtime() + KEYGUARD_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isLocked()) {
                stable++
                if (stable >= KEYGUARD_STABLE_SAMPLES) return true
            } else {
                stable = 0
            }
            delay(POLL_INTERVAL_MS)
        }
        return isLocked()
    }

    private suspend fun awaitUnlocked(deadline: Long): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        var unlockedSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancelled) return false
            if (!isLocked()) {
                unlockedSamples++
                if (unlockedSamples >= UNLOCK_CONFIRM_SAMPLES &&
                    SystemClock.elapsedRealtime() - startedAt >= MIN_RECORD_MS
                ) {
                    return true
                }
            } else {
                unlockedSamples = 0
            }
            delay(POLL_INTERVAL_MS)
        }
        return !isLocked()
    }

    private suspend fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            delay(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun stopGetevent(controller: DeviceController, path: String) {
        runCatching {
            controller.shell("pkill -f \"getevent -lt $path\"")
        }
    }

    private fun isLocked(): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }.getOrDefault(false)

    private fun isInteractive(): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
    }.getOrDefault(true)

    private fun screenRotation(): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: 0
    }.getOrDefault(0)

    private companion object {
        const val RECORD_TIMEOUT_SEC = 60
        const val RECORD_TIMEOUT_MS = 60_000L
        const val PRIVILEGED_RECORD_TIMEOUT_MS = 90_000L
        const val PRIVILEGED_RECORD_GRACE_MS = 15_000L
        const val PRIVILEGED_POLL_INTERVAL_MS = 500L
        const val PRIVILEGED_FALLBACK_DELAY_MS = 500L
        const val TEST_LOCK_TIMEOUT_MS = 4_000L
        const val WAKE_TIMEOUT_MS = 3_000L
        const val KEYGUARD_SETTLE_MS = 600L
        const val KEYGUARD_WAIT_MS = 2_500L
        const val KEYGUARD_STABLE_SAMPLES = 3
        const val TAIL_AFTER_UNLOCK_MS = 250L
        const val MIN_RECORD_MS = 800L
        const val UNLOCK_CONFIRM_SAMPLES = 2
        const val POLL_INTERVAL_MS = 100L
    }
}
