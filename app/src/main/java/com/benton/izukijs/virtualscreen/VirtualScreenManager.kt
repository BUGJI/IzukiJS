package com.benton.izukijs.virtualscreen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Surface
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.privileged.VirtualScreenServiceClient
import com.benton.izukijs.core.log.LogBus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 虚拟屏生命周期状态，供设置页、运行页与 PiP 武装逻辑订阅。 */
sealed interface VirtualScreenState {
    data object Idle : VirtualScreenState

    data object Starting : VirtualScreenState

    data class Active(
        val displayId: Int,
        val resolution: VirtualScreenResolution,
    ) : VirtualScreenState

    data class Failed(val message: String) : VirtualScreenState
}

data class VirtualScreenOptions(
    val resolution: VirtualScreenResolution,
)

/**
 * 后台虚拟屏的 App 侧门面。
 *
 * 负责：创建/销毁特权虚拟屏、维护 `ControllerManager.targetDisplayId`、采集帧、
 * 维护 PiP / 页面预览面与虚拟屏输入。运行模式与权限判断由调用方
 * （ScriptExecutionManager / Script API）完成。
 */
class VirtualScreenManager(
    private val context: Context,
    private val controllers: ControllerManager,
    private val settingsRepository: VirtualScreenSettingsRepository,
    private val logBus: LogBus,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = VirtualScreenServiceClient(context, logBus)
    private val previewRenderer = VirtualScreenPreviewRenderer(logBus)
    private val startMutex = Mutex()

    private val _state = MutableStateFlow<VirtualScreenState>(VirtualScreenState.Idle)
    val state: StateFlow<VirtualScreenState> = _state.asStateFlow()

    private val _previewAttached = MutableStateFlow(false)
    val previewAttached: StateFlow<Boolean> = _previewAttached.asStateFlow()

    @Volatile
    private var frameSource: VirtualScreenFrameSource? = null

    private val previewSurfaces = ConcurrentHashMap<String, Surface>()

    @Volatile
    private var activeResolution: VirtualScreenResolution? = null

    @Volatile
    private var targetMode: ScreenTarget = ScreenTarget.AUTO

    /** 脚本是否显式调用过 target；未显式指定时允许 start() 自动指向虚拟屏。 */
    @Volatile
    private var targetExplicit: Boolean = false

    /** 每次真正创建/重建虚拟屏自增；脚本运行结束时据此判断是否由本次脚本启动。 */
    @Volatile
    private var startGenerationValue: Long = 0L

    @Volatile
    private var lastStopReasonValue: String? = null

    @Volatile
    private var lastStopAtValue: Long = 0L

    private var heartbeatJob: Job? = null

    init {
        val runtime = settingsRepository.runtimeState()
        lastStopReasonValue = runtime.lastStopReason
        lastStopAtValue = runtime.lastStopAt
        if (runtime.wasActive) {
            // 上次进程退出时虚拟屏仍处于激活状态：服务随进程结束，记录原因供下次诊断。
            lastStopReasonValue = STOP_APP_RESTART
            lastStopAtValue = System.currentTimeMillis()
            settingsRepository.recordStop(STOP_APP_RESTART, lastStopAtValue)
            logBus.warn("上次运行结束时虚拟屏仍处于激活状态，服务已随进程释放")
        }
        scope.launch {
            client.connected.collect { connected ->
                if (!connected && _state.value is VirtualScreenState.Active) {
                    logBus.warn("虚拟屏特权服务断开，正在清理虚拟屏")
                    // 与 start()/stop() 共用 startMutex，避免断连清理和正在进行的启停互相踩踏。
                    startMutex.withLock {
                        if (_state.value is VirtualScreenState.Active) {
                            cleanupInternal(
                                destroyRemote = false,
                                reason = STOP_SERVICE_DISCONNECTED,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Shizuku 特权后端是否可用；不检查用户是否允许后台模式。 */
    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        client.isShizukuAvailable()

    fun canStartBackground(): Boolean =
        available() && settingsRepository.current().backgroundAllowed

    fun allowedModes(): Set<com.benton.izukijs.model.RunMode> =
        settingsRepository.current().allowedModes

    fun configuredResolution(): VirtualScreenResolution =
        settingsRepository.current().resolution

    fun isActive(): Boolean = _state.value is VirtualScreenState.Active

    fun displayId(): Int =
        (_state.value as? VirtualScreenState.Active)?.displayId ?: DISPLAY_NONE

    fun size(): VirtualScreenResolution? = activeResolution

    fun target(): ScreenTarget = targetMode

    /**
     * 每个脚本开始时按运行模式恢复默认目标：
     * 后台脚本使用 AUTO（虚拟屏激活时指向虚拟屏），前台脚本使用主屏。
     * 这样保留下来的虚拟屏不会把下一次前台脚本的输入带到虚拟屏。
     */
    fun resetTarget(runMode: RunMode) {
        targetMode = ScreenTarget.defaultFor(runMode)
        targetExplicit = false
        applyTarget()
    }

    /** 切换脚本操作目标；AUTO 在虚拟屏激活时自动指向虚拟屏。 */
    fun setTarget(value: ScreenTarget): Boolean {
        targetMode = value
        targetExplicit = true
        applyTarget()
        logBus.info("操作目标已切换为 ${value.displayName()}")
        return true
    }

    /** 已成功创建/重建虚拟屏的次数，用于判断脚本是否拥有本次虚拟屏。 */
    fun startGeneration(): Long = startGenerationValue

    /** 当前实际生效的目标：AUTO 在虚拟屏激活时解析为虚拟屏，否则主屏。 */
    fun resolvedTarget(): ScreenTarget = when (targetMode) {
        ScreenTarget.AUTO -> if (isActive()) ScreenTarget.VIRTUAL else ScreenTarget.MAIN
        ScreenTarget.VIRTUAL -> if (isActive()) ScreenTarget.VIRTUAL else ScreenTarget.MAIN
        ScreenTarget.MAIN -> ScreenTarget.MAIN
    }

    suspend fun start(options: VirtualScreenOptions? = null): Boolean = startMutex.withLock {
        val resolution = (options?.resolution ?: settingsRepository.current().resolution)
            .normalized()
        val current = _state.value
        if (current is VirtualScreenState.Active) {
            if (current.resolution == resolution) {
                if (!targetExplicit) targetMode = ScreenTarget.AUTO
                applyTarget()
                return@withLock true
            }
            logBus.info(
                "虚拟屏分辨率变化，正在重建" +
                    "（${current.resolution.width}x${current.resolution.height}" +
                    " → ${resolution.width}x${resolution.height}）",
            )
            cleanupInternal(destroyRemote = true, reason = STOP_RESOLUTION_CHANGED)
        }
        if (!available()) {
            fail("需要 Shizuku 授权，且系统版本需为 Android 10+")
            return@withLock false
        }
        if (!settingsRepository.current().backgroundAllowed) {
            fail("未在设置中允许后台虚拟屏")
            return@withLock false
        }
        _state.value = VirtualScreenState.Starting
        logBus.info("正在创建后台虚拟屏（${resolution.width}x${resolution.height}@${resolution.dpi}）")

        val source = VirtualScreenFrameSource(resolution.width, resolution.height, logBus)
        try {
            source.start()
        } catch (t: Throwable) {
            source.close()
            if (t is CancellationException) throw t
            fail("虚拟屏帧源创建失败：${t.message}")
            return@withLock false
        }
        val displayId = try {
            client.create(
                resolution.width,
                resolution.height,
                resolution.dpi,
                source.surface,
                settingsRepository.current().showSystemDecorations,
            )
        } catch (t: Throwable) {
            source.close()
            if (t is CancellationException) {
                // 取消可能发生在远程 display 已创建之后；尽力清理，避免遗留虚拟屏。
                withContext(NonCancellable) {
                    runCatching { client.destroyDisplay() }
                    _state.value = VirtualScreenState.Idle
                }
                throw t
            }
            fail("虚拟屏创建失败：${t.message}")
            return@withLock false
        }
        if (displayId == null || displayId < 0) {
            source.close()
            val detail = client.lastError()
            fail(
                if (detail != null) {
                    "虚拟屏创建失败：$detail"
                } else {
                    "虚拟屏创建失败，请检查 Shizuku 权限或 ROM 限制"
                },
            )
            return@withLock false
        }

        frameSource = source
        activeResolution = resolution
        previewRenderer.setFrameSource(source)
        previewSurfaces.forEach { (owner, surface) -> previewRenderer.attach(owner, surface) }
        _state.value = VirtualScreenState.Active(displayId, resolution)
        startGenerationValue++
        settingsRepository.saveResolution(resolution)
        settingsRepository.markActive(true)
        startHeartbeat()
        if (!targetExplicit) targetMode = ScreenTarget.AUTO
        applyTarget()
        logBus.success("后台虚拟屏已启动（display=$displayId）")
        true
    }

    fun startBlocking(options: VirtualScreenOptions? = null): Boolean =
        runCatching {
            runBlocking { withTimeout(START_TIMEOUT_MS) { start(options) } }
        }.getOrElse {
            logBus.error("启动虚拟屏超时或失败：${it.message}")
            false
        }

    suspend fun stop(reason: String = STOP_USER) {
        startMutex.withLock {
            // 页面退出会取消发起 stop 的协程；清理必须跑完，否则 remote display 会泄漏。
            withContext(NonCancellable) {
                cleanupInternal(destroyRemote = true, reason = reason)
            }
        }
    }

    fun stopBlocking(reason: String = STOP_USER) {
        runCatching {
            runBlocking { withTimeout(STOP_TIMEOUT_MS) { stop(reason) } }
        }.onFailure {
            logBus.warn("停止虚拟屏超时或失败：${it.message}")
        }
    }

    /** 取最新一帧；[waitMs] > 0 时等待首帧。返回的 Bitmap 由调用方负责 recycle。 */
    fun capture(waitMs: Long = 0L): Bitmap? = frameSource?.captureLatest(waitMs)

    fun awaitFrame(timeoutMs: Long = DEFAULT_FRAME_WAIT_MS): Boolean =
        frameSource?.awaitFrame(timeoutMs) == true

    fun canCapture(): Boolean = isActive() && frameSource != null

    /** 最近一次停止原因：user_stop / service_disconnected / app_restart / resolution_changed / run_finished。 */
    fun lastStopReason(): String? = lastStopReasonValue

    fun lastStopAt(): Long = lastStopAtValue

    fun captureAfter(frameSeq: Long, timeoutMs: Long = 500L): Bitmap? =
        frameSource?.capture(frameSeq, timeoutMs)

    /** 等待比 [frameSeq] 更新的帧；用于输入后确认画面已重绘。 */
    fun awaitFrameAfter(frameSeq: Long, timeoutMs: Long = 600L): Boolean =
        frameSource?.awaitFrameAfter(frameSeq, timeoutMs) == true

    fun frameSeq(): Long = frameSource?.currentSeq() ?: 0L

    /** 虚拟屏上当前前台包名；未激活或服务不可用时返回 null。 */
    fun currentPackageBlocking(): String? = runCatching {
        runBlocking {
            withTimeout(PACKAGE_QUERY_TIMEOUT_MS) {
                if (!isActive()) null else client.currentPackage()
            }
        }
    }.getOrNull()

    suspend fun currentPackage(): String? =
        if (!isActive()) null else client.currentPackage()

    /** 绑定 PiP 预览面。 */
    fun setPreviewSurface(surface: Surface?) = attachPreviewSurface(PIP_PREVIEW_OWNER, surface)

    /**
     * 绑定/解绑某个预览面。页面与 PiP 使用不同 owner，互不抢占 Surface。
     * 只影响本进程绘制，不触碰特权服务。
     */
    fun attachPreviewSurface(owner: String, surface: Surface?) {
        if (surface == null) {
            previewSurfaces.remove(owner)
            previewRenderer.detach(owner, null)
        } else {
            previewSurfaces[owner] = surface
            previewRenderer.attach(owner, surface)
            if (isActive()) {
                frameSource?.let { previewRenderer.setFrameSource(it) }
            }
        }
        if (owner == PIP_PREVIEW_OWNER) {
            _previewAttached.value = surface != null
        }
    }

    fun detachPreviewSurface(owner: String, surface: Surface?) {
        val current = previewSurfaces[owner]
        if (surface != null && current !== surface) return
        previewSurfaces.remove(owner)
        previewRenderer.detach(owner, surface)
        if (owner == PIP_PREVIEW_OWNER) {
            _previewAttached.value = false
        }
    }

    /** 在虚拟屏上执行点击；使用显式 displayId，不改变脚本的全局操作目标。 */
    suspend fun tap(x: Float, y: Float): Boolean =
        inputCommand { id -> "input -d $id tap ${x.toInt()} ${y.toInt()}" }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 600L): Boolean =
        inputCommand { id ->
            val ix = x.toInt()
            val iy = y.toInt()
            "input -d $id swipe $ix $iy $ix $iy ${durationMs.coerceAtLeast(400L)}"
        }

    suspend fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
    ): Boolean = inputCommand { id ->
        "input -d $id swipe ${x1.toInt()} ${y1.toInt()} " +
            "${x2.toInt()} ${y2.toInt()} ${durationMs.coerceIn(1L, MAX_INPUT_DURATION_MS)}"
    }

    suspend fun key(keyCode: Int): Boolean =
        inputCommand { id -> "input -d $id keyevent $keyCode" }

    private suspend fun inputCommand(build: (displayId: Int) -> String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isActive()) return@withContext false
            val displayId = displayId()
            if (displayId < 0) return@withContext false
            val controller = controllers.controllerFor(Capability.SHELL) ?: run {
                logBus.warn("没有可用的 Shell 后端，无法操作虚拟屏")
                return@withContext false
            }
            val command = build(displayId)
            val result = controller.shell(command) ?: return@withContext false
            if (result.exitCode != 0) {
                logBus.warn("虚拟屏输入失败（exit=${result.exitCode}）：$command")
            }
            result.exitCode == 0
        }

    suspend fun launch(packageName: String): Boolean {
        val intent = resolveLaunchIntent(packageName) ?: run {
            logBus.warn("无法创建 $packageName 的启动 Intent")
            return false
        }
        return launchIntent(intent, forceStopIfElsewhere = true)
    }

    fun launchBlocking(packageName: String): Boolean = runCatching {
        runBlocking { withTimeout(LAUNCH_TIMEOUT_MS) { launch(packageName) } }
    }.getOrDefault(false)

    /** 把任意 Intent 定向到虚拟屏；[forceStopIfElsewhere] 为 false 时尽量迁移而不强停。 */
    suspend fun launchIntent(
        intent: Intent,
        forceStopIfElsewhere: Boolean = true,
    ): Boolean {
        if (!isActive()) {
            logBus.warn("虚拟屏未运行，无法启动 ${packageOf(intent) ?: intent}")
            return false
        }
        val displayId = displayId()
        val label = packageOf(intent) ?: intent.action ?: intent.toString()
        val started = client.launch(intent, forceStopIfElsewhere) == true
        if (started) {
            logBus.info("已在虚拟屏 display=$displayId 启动 $label")
            return true
        }
        return launchViaShell(intent, displayId, label)
    }

    fun launchIntentBlocking(
        intent: Intent,
        forceStopIfElsewhere: Boolean = true,
    ): Boolean = runCatching {
        runBlocking {
            withTimeout(LAUNCH_TIMEOUT_MS) { launchIntent(intent, forceStopIfElsewhere) }
        }
    }.getOrDefault(false)

    private fun resolveLaunchIntent(packageName: String): Intent? {
        val component = packageName.takeIf { it.contains('/') }
            ?.let { ComponentName.unflattenFromString(it) }
        val intent = if (component != null) {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)
        } ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        return intent
    }

    private fun packageOf(intent: Intent): String? =
        intent.component?.packageName
            ?: runCatching { intent.resolveActivity(context.packageManager)?.packageName }.getOrNull()

    private fun launchViaShell(intent: Intent, displayId: Int, label: String): Boolean {
        val component = intent.component
        if (component == null) {
            logBus.warn("虚拟屏启动回落失败：$label 没有组件信息")
            return false
        }
        val command = "am start --display $displayId -n ${component.flattenToShortString()}"
        val result = controllers.controllerFor(Capability.SHELL)?.shell(command)
        val ok = result?.exitCode == 0
        if (ok) {
            logBus.info("已在虚拟屏 display=$displayId 启动 $label（Shell 回落）")
        } else {
            logBus.warn("虚拟屏启动失败：$label（exit=${result?.exitCode}）")
        }
        return ok
    }

    private fun applyTarget() {
        val displayId = (_state.value as? VirtualScreenState.Active)?.displayId
        controllers.targetDisplayId = when (targetMode) {
            ScreenTarget.MAIN -> null
            ScreenTarget.AUTO, ScreenTarget.VIRTUAL -> displayId
        }
    }

    private suspend fun cleanupInternal(destroyRemote: Boolean, reason: String? = null) {
        val hadState = _state.value !is VirtualScreenState.Idle
        heartbeatJob?.cancel()
        heartbeatJob = null
        targetMode = ScreenTarget.AUTO
        targetExplicit = false
        controllers.targetDisplayId = null
        previewRenderer.setFrameSource(null)
        previewRenderer.stop()
        previewRenderer.clear()
        if (destroyRemote) {
            client.destroyDisplay()
        }
        frameSource?.close()
        frameSource = null
        activeResolution = null
        if (reason != null) {
            lastStopReasonValue = reason
            lastStopAtValue = System.currentTimeMillis()
            settingsRepository.recordStop(reason, lastStopAtValue)
        }
        if (hadState) {
            logBus.info("后台虚拟屏已停止（原因：${reason ?: STOP_UNKNOWN}）")
        }
        _state.value = VirtualScreenState.Idle
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (_state.value is VirtualScreenState.Active) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_state.value !is VirtualScreenState.Active) break
                client.ping()
            }
        }
    }

    private fun fail(message: String): Boolean {
        logBus.warn("后台虚拟屏不可用：$message")
        _state.value = VirtualScreenState.Failed(message)
        return false
    }

    companion object {
        const val PIP_PREVIEW_OWNER = "pip"
        const val PAGE_PREVIEW_OWNER = "page"

        private const val DISPLAY_NONE = -1
        private const val DEFAULT_FRAME_WAIT_MS = 1_000L
        private const val MAX_INPUT_DURATION_MS = 60_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 阻塞桥的硬超时：仅在 JS / MCP 同步入口使用，避免单线程执行器被永久占用。 */
        private const val START_TIMEOUT_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 15_000L
        private const val LAUNCH_TIMEOUT_MS = 15_000L
        private const val PACKAGE_QUERY_TIMEOUT_MS = 10_000L

        const val STOP_USER = "user_stop"
        const val STOP_SERVICE_DISCONNECTED = "service_disconnected"
        const val STOP_APP_RESTART = "app_restart"
        const val STOP_RESOLUTION_CHANGED = "resolution_changed"
        const val STOP_RUN_FINISHED = "run_finished"
        const val STOP_UNKNOWN = "unknown"
    }
}
