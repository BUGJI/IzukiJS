package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.NodeTreeResult
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.ScriptApiEnvironment
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * QuickJS 运行时封装。每个脚本一个实例，拥有独立的运行时与上下文。
 */
class JsEngine(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val deviceApiBundle: DeviceApiBundle,
    private val nodeTreeProvider: (timeoutMs: Long) -> NodeTreeResult,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val scriptEnv: ScriptEnvSession,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val moduleResolver: com.benton.izukijs.data.ScriptModuleResolver,
    private val virtualScreen: VirtualScreenManager? = null,
    private val runMode: RunMode = RunMode.FOREGROUND,
    private val scriptId: String = "",
    private val scriptName: String = "",
    private val runId: String? = null,
    private val pushDispatcher: NotificationDispatcher? = null,
    private val recordingSession: com.benton.izukijs.controller.record.RecordingSession? = null,
    /** 当前 run 是否仍持有运行位；用于阻止被终止的旧脚本抢回虚拟屏 / 通知。 */
    private val isRunActive: () -> Boolean = { true },
) : Closeable {

    private var runtime: QuickJS? = null
    private var jsContext: JSContext? = null
    private var apiEnvironment: ScriptApiEnvironment? = null

    private val preludeSource: String by lazy {
        context.assets.open(PRELUDE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private val compatSource: String by lazy {
        context.assets.open(COMPAT_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Volatile
    private var interrupt: QuickJsInterrupt? = null

    @Volatile
    private var watchdog: Thread? = null

    @Volatile
    var exitRequested: Boolean = false
        private set

    /** 兼容定时器等待 / 唤醒用；停止请求可立即打断轮询。 */
    private val exitLock = ReentrantLock()
    private val exitSignal = exitLock.newCondition()

    /** 是否因运行超时被中断（与用户主动停止区分）。 */
    @Volatile
    var timedOut: Boolean = false
        private set

    fun requestExit() {
        exitRequested = true
        interrupt?.stop()
        exitLock.lock()
        try {
            exitSignal.signalAll()
        } finally {
            exitLock.unlock()
        }
    }

    /**
     * @param timeoutMs 运行超时（毫秒），<= 0 表示不限制。超时由看门狗请求退出，
     *   并通过 [QuickJsInterrupt] 打断纯计算死循环。
     */
    fun execute(
        source: String,
        fileName: String,
        timeoutMs: Long = 0L,
        needs: Set<String>? = null,
    ) {
        val compat = CompatMode.analyze(source)
        if (compat.enabled) {
            logBus.info("🧩 兼容模式: ${compat.flavor}")
            compat.unsupportedMessage()?.let { message ->
                throw IllegalArgumentException(message)
            }
            compat.issues
                .filter { it.code != "unsupported-flavor" }
                .forEach { logBus.warn(it.message) }
        } else {
            compat.issues.firstOrNull { it.code == "unsupported-flavor" }?.let {
                logBus.warn(it.message)
            }
        }

        val rt = QuickJS.createRuntimeWithEventQueue()
        runtime = rt
        val installed = QuickJsInterrupt.install(rt)
        interrupt = installed
        if (exitRequested) installed?.stop()
        val ctx = rt.createContext()
        jsContext = ctx
        val env = ScriptApiEnvironment(
            context = context,
            controllers = controllers,
            screenCapture = screenCapture,
            bundle = deviceApiBundle,
            nodeTreeProvider = nodeTreeProvider,
            logBus = logBus,
            aiConfigRepository = aiConfigRepository,
            captureSettingsRepository = captureSettingsRepository,
            scriptEnv = scriptEnv,
            runtimeSettings = runtimeSettings,
            httpSettingsRepository = httpSettingsRepository,
            moduleResolver = moduleResolver,
            virtualScreen = virtualScreen,
            runMode = runMode,
            scriptId = scriptId,
            scriptName = scriptName,
            runId = runId,
            pushDispatcher = pushDispatcher,
            recordingSession = recordingSession,
            exitRequested = { exitRequested },
            onExit = { requestExit() },
            isRunActive = isRunActive,
        )
        apiEnvironment = env
        // 兼容层的门面依赖这些命名空间；老脚本通常不写 @need，但用户补了 @need
        // 时不应让兼容门面静默消失。@need 只控制注册，运行期能力校验仍在各 API 内。
        val effectiveNeeds = if (compat.enabled && needs != null) needs + COMPAT_NEEDS else needs
        env.bind(ctx, effectiveNeeds)
        startWatchdog(timeoutMs)
        try {
            ctx.executeVoidScript(preludeSource, "izuki_prelude.js")
            if (compat.enabled) {
                ctx.executeVoidScript(compatSource, COMPAT_ASSET_NAME)
            }
            ctx.executeVoidScript(source, fileName)
            if (compat.enabled) {
                drainCompatTimers(ctx)
            }
        } finally {
            stopWatchdog()
        }
    }

    /**
     * 兼容脚本的定时器是 JS 队列，必须在同一个运行时线程上轮询执行。
     * 主脚本结束后继续保活，直到没有待执行定时器、用户停止或达到保活上限。
     */
    private fun drainCompatTimers(ctx: JSContext) {
        val deadline = System.nanoTime() + COMPAT_KEEPALIVE_MS * 1_000_000L
        while (!exitRequested && System.nanoTime() < deadline) {
            val pending = runCatching {
                ctx.executeBooleanScript(COMPAT_PENDING_SCRIPT, COMPAT_PENDING_NAME)
            }.getOrDefault(false)
            if (!pending) return
            val pump = runCatching {
                ctx.executeVoidScript(COMPAT_PUMP_SCRIPT, COMPAT_PUMP_NAME)
            }
            val error = pump.exceptionOrNull()
            if (error != null) {
                if (error.message?.contains("IZUKI_SCRIPT_EXIT") == true) return
                logBus.error("兼容定时器回调异常: ${error.message}")
                return
            }
            // 按“下一个到期时间”休眠，避免 setInterval(60s) 也按 16ms 空转；
            // 停止请求通过 exitSignal 立即唤醒。
            val nextDelay = runCatching {
                ctx.executeDoubleScript(COMPAT_NEXT_DELAY_SCRIPT, COMPAT_NEXT_DELAY_NAME)
            }.getOrDefault(COMPAT_POLL_MS.toDouble())
            if (!waitForCompatTimer(nextDelay)) return
        }
        if (!exitRequested) {
            logBus.warn("兼容定时器达到保活上限，脚本结束")
        }
    }

    /** @return false 表示收到停止 / 中断，调用方应结束。 */
    private fun waitForCompatTimer(nextDelayMs: Double): Boolean {
        val waitMs = if (nextDelayMs < 0) {
            COMPAT_POLL_MS
        } else {
            nextDelayMs.toLong().coerceIn(COMPAT_POLL_MS, COMPAT_MAX_SLEEP_MS)
        }
        exitLock.lock()
        try {
            if (exitRequested) return false
            try {
                exitSignal.await(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return false
            }
        } finally {
            exitLock.unlock()
        }
        return !exitRequested
    }

    private fun startWatchdog(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        val thread = Thread({
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) {
                        if (!exitRequested) {
                            timedOut = true
                            requestExit()
                        }
                        return@Thread
                    }
                    Thread.sleep(minOf(WATCHDOG_INTERVAL_MS, remaining / 1_000_000L + 1L))
                }
            } catch (_: InterruptedException) {
                // 脚本已结束或停止，正常退出。
            }
        }, "izuki-script-timeout").apply { isDaemon = true }
        watchdog = thread
        thread.start()
    }

    private fun stopWatchdog() {
        watchdog?.interrupt()
        watchdog = null
    }

    override fun close() {
        stopWatchdog()
        runCatching { apiEnvironment?.close() }
        apiEnvironment = null
        runCatching { interrupt?.close() }
        interrupt = null
        runCatching { jsContext?.close() }
        runCatching { runtime?.close() }
        jsContext = null
        runtime = null
    }

    private companion object {
        const val WATCHDOG_INTERVAL_MS = 200L

        /** 放在 runtime/ 下，避免被 ScriptRepository.seedBundledScripts 当成用户脚本复制。 */
        const val PRELUDE_ASSET = "runtime/prelude.js"

        /** 仅在 `// @compat autojs6` 脚本中加载的旧脚本门面。 */
        const val COMPAT_ASSET = "runtime/compat/autojs6-core.js"
        const val COMPAT_ASSET_NAME = "izuki_autojs6_compat.js"

        /** 兼容层 `available()` 依赖的命名空间；脚本显式写了 @need 时一并注册。 */
        private val COMPAT_NEEDS = setOf(
            "app", "clipboard", "dialogs", "events", "fs", "http",
            "images", "selector", "sensors", "storages",
        )

        const val COMPAT_PENDING_SCRIPT =
            "typeof __izukiCompatHasPending === 'function' && __izukiCompatHasPending()"
        const val COMPAT_PUMP_SCRIPT =
            "typeof __izukiCompatPump === 'function' && __izukiCompatPump()"
        const val COMPAT_NEXT_DELAY_SCRIPT =
            "typeof __izukiCompatNextDelay === 'function' ? __izukiCompatNextDelay() : -1"
        const val COMPAT_PENDING_NAME = "izuki_compat_pending.js"
        const val COMPAT_PUMP_NAME = "izuki_compat_pump.js"
        const val COMPAT_NEXT_DELAY_NAME = "izuki_compat_next_delay.js"
        const val COMPAT_POLL_MS = 16L

        /**
         * 单次最长休眠。停止请求通过 exitSignal 立即唤醒，运行超时由 watchdog 触发，
         * 因此可以睡到下一个定时器到期，避免长间隔 setInterval 每秒空转一次。
         */
        const val COMPAT_MAX_SLEEP_MS = 60_000L

        /** setInterval 等长期定时器的保活上限；停止与运行超时仍优先。 */
        const val COMPAT_KEEPALIVE_MS = 24L * 60L * 60L * 1000L
    }
}
