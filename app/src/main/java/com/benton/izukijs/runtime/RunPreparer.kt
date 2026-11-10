package com.benton.izukijs.runtime

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.notification.ScriptNotificationCenter
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.data.ScriptEnvSession
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.model.ScriptEnvSpec
import com.benton.izukijs.service.FloatingWindowService
import com.benton.izukijs.service.ScriptForegroundService
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository

/** 一次运行在真正执行脚本前准备好的环境。 */
internal data class PreparedRun(
    val scriptEnv: ScriptEnvSession,
    val plannedRunMode: RunMode,
    val virtualSettings: VirtualScreenSettings,
)

/**
 * 运行准备阶段：解析 `@env`、合并参数、落盘历史、解析运行模式，并拉起前台服务 / 悬浮窗。
 *
 * 从 [ScriptExecutionManager] 中拆出后，执行器只负责状态机与脚本生命周期，
 * 准备阶段可以独立测试，也不会把配置仓库的依赖继续堆到执行器上。
 */
class RunPreparer(
    private val appContext: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val scriptEnvRepository: ScriptEnvRepository,
    private val globalEnvRepository: GlobalEnvRepository,
    private val runHistoryRepository: RunHistoryRepository,
    private val virtualScreen: VirtualScreenManager,
    private val virtualScreenSettings: VirtualScreenSettingsRepository,
    private val runtimeSettings: RuntimeSettingsRepository,
) {

    internal fun prepare(
        runId: String,
        scriptId: String,
        scriptName: String,
        source: String,
        values: Map<String, String>,
        needs: Set<String>?,
        onStarted: (scriptName: String, scriptId: String, runId: String) -> Unit,
    ): PreparedRun {
        // 解析脚本头部声明的运行参数，按「默认值 < 全局变量 < 脚本变量 < 本次传入值」合并。
        val spec = ScriptEnvSpec.parse(source)
        val useGlobal = scriptEnvRepository.useGlobalEnv(scriptId)
        val resolved = EnvText.resolve(
            defaults = spec.defaults(),
            global = globalEnvRepository.current(),
            script = scriptEnvRepository.env(scriptId),
            runtime = scriptEnvRepository.values(scriptId) + values,
            useGlobal = useGlobal,
        )
        if (values.isNotEmpty()) {
            // 只持久化本次表单输入与已有脚本参数，避免把全局变量快照写进脚本参数。
            scriptEnvRepository.saveValues(
                scriptId,
                spec.defaults() + scriptEnvRepository.values(scriptId) + values,
            )
        }
        val scriptEnv = ScriptEnvSession(scriptId, resolved, scriptEnvRepository)
        if (resolved.isNotEmpty()) {
            // 只记录键名，避免把可能含密钥的变量值写入日志。
            logBus.info("⚙ 运行参数: " + resolved.keys.sorted().joinToString(", "))
        }
        val virtualSettings = virtualScreenSettings.current()
        val plannedRunMode = resolveRunMode(resolved, virtualSettings)

        onStarted(scriptName, scriptId, runId)
        runHistoryRepository.record(scriptId, scriptName)
        logBus.info("▶ 开始运行: $scriptName")
        if (needs != null) {
            logBus.info("🔒 声明能力: " + needs.sorted().joinToString(", "))
            warnMissingNeeds(needs)
        }

        ScriptNotificationCenter.begin(scriptName)
        runCatching { ScriptForegroundService.start(appContext, scriptName) }.onFailure {
            logBus.warn("前台服务启动失败，脚本将继续运行但可能被系统回收：${it.message}")
        }
        val floatingMode = runtimeSettings.current().floatingWindowMode
        if (floatingMode != FloatingWindowMode.OFF) {
            runCatching {
                FloatingWindowService.showForRun(
                    appContext,
                    collapsed = floatingMode == FloatingWindowMode.COLLAPSED,
                )
            }.onFailure {
                logBus.warn("悬浮窗启动失败：${it.message}")
            }
        }
        return PreparedRun(scriptEnv, plannedRunMode, virtualSettings)
    }

    private fun resolveRunMode(
        resolved: Map<String, String>,
        virtualSettings: VirtualScreenSettings,
    ): RunMode {
        return RunModeResolver.resolve(
            requested = RunMode.from(resolved[RUN_MODE_KEY]),
            settings = virtualSettings,
            virtualScreenAvailable = virtualScreen.available(),
            onFallback = { logBus.warn("后台虚拟屏不可用或未允许，本次回退前台模式") },
        )
    }

    private fun warnMissingNeeds(needs: Set<String>) {
        if ("http" in needs && !httpSettingsRepository.current().enabled) {
            logBus.warn("@need http 已声明，但「脚本网络」未启用")
        }
        if ("shell" in needs && controllers.controllerFor(Capability.SHELL)?.isReady() != true) {
            logBus.warn("@need shell 已声明，但 Shizuku / 无线调试 / Root 未就绪")
        }
        if ("selector" in needs &&
            controllers.controllerFor(Capability.NODE_TREE)?.isReady() != true
        ) {
            logBus.warn("@need selector 已声明，但无障碍未就绪")
        }
        if ("notify" in needs &&
            !NotificationManagerCompat.getEnabledListenerPackages(appContext)
                .contains(appContext.packageName)
        ) {
            logBus.warn("@need notify 已声明，但通知监听未授权")
        }
        if ("ai" in needs && !aiConfigRepository.current().isConfigured) {
            logBus.warn("@need ai 已声明，但 AI Agent 尚未配置")
        }
        if ("fs" in needs &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            logBus.warn("@need fs 已声明，但未授予「全部文件访问」；仅应用私有目录可用")
        }
        if ("virtualscreen" in needs && !virtualScreen.available()) {
            logBus.warn("@need virtualscreen 已声明，但 Shizuku 未授权或系统版本低于 Android 10")
        }
        if ("selector" in needs && virtualScreen.isActive()) {
            logBus.warn("@need selector 在后台虚拟屏上不可用，请改用 capture / ocr / 坐标输入")
        }
    }

    private companion object {
        /** 脚本头部 `@env RUN_MODE=background` 使用的键名。 */
        const val RUN_MODE_KEY = "RUN_MODE"
    }
}
