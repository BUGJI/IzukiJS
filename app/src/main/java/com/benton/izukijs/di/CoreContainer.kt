package com.benton.izukijs.di

import android.app.Application
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.log.LogBusRunLogSink
import com.benton.izukijs.data.AppearanceRepository
import com.benton.izukijs.data.EditorSettingsRepository
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.data.RunHistoryRepository
import com.benton.izukijs.data.ScriptEnvRepository
import com.benton.izukijs.data.ScriptImporter
import com.benton.izukijs.data.ScriptListPreferences
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.data.ScriptStorageRepository
import com.benton.izukijs.data.TileSettingsRepository
import com.benton.izukijs.data.UnlockSettingsRepository
import com.benton.izukijs.developer.DeveloperSettingsRepository
import com.benton.izukijs.flow.FlowRepository
import com.benton.izukijs.i18n.LanguageRepository
import com.benton.izukijs.notification.NotificationDispatcher
import com.benton.izukijs.notification.NotificationSettingsRepository
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.runtime.CacheImageStore
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.runtime.LogFileStore
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.runtime.RuntimeSettingsRepository
import com.benton.izukijs.schedule.ScheduleRepository
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.subscription.SubscriptionRepository
import com.benton.izukijs.update.UpdateManager
import com.benton.izukijs.update.UpdateSettingsRepository
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级基础图：作用域、日志、仓库与设置。
 *
 * 只依赖 Android `Application`，不碰控制后端、脚本运行时或 MCP，避免各方互相拉扯。
 */
class CoreContainer(val application: Application) {

    /** 后台任务作用域：日志、订阅、定时任务等长生命周期工作。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 应用级 UI 作用域：Snackbar 结果等待等需要在页面切换后继续的操作。 */
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val logBus = LogBus()

    val logSettingsRepository = LogSettingsRepository(application)

    val logFileStore = LogFileStore(application, logSettingsRepository)

    val cacheImageStore = CacheImageStore(application)

    val scriptStorageRepository = ScriptStorageRepository(application)

    val scriptRepository = ScriptRepository(application, scriptStorageRepository)

    val scriptImporter = ScriptImporter(application, scriptRepository, logBus)

    val scriptListPreferences = ScriptListPreferences(application)

    val scriptEnvRepository = ScriptEnvRepository(application)

    val globalEnvRepository = GlobalEnvRepository(application)

    val runHistoryRepository = RunHistoryRepository(application)

    val tileSettingsRepository = TileSettingsRepository(application)

    val runtimeSettingsRepository = RuntimeSettingsRepository(application)

    val notificationSettingsRepository = NotificationSettingsRepository(application)

    val notificationDispatcher = NotificationDispatcher(
        context = application,
        settingsRepository = notificationSettingsRepository,
        logBus = logBus,
    )

    val virtualScreenSettingsRepository = VirtualScreenSettingsRepository(application)

    val httpSettingsRepository = HttpSettingsRepository(application)

    val unlockSettingsRepository = UnlockSettingsRepository(application)

    val ocrConfigRepository = OcrConfigRepository(application)

    val aiConfigRepository = AiConfigRepository(application)

    val editorSettingsRepository = EditorSettingsRepository(application)

    val languageRepository = LanguageRepository(application)

    val appearanceRepository = AppearanceRepository(application)

    val developerSettingsRepository = DeveloperSettingsRepository(application)

    val updateSettingsRepository = UpdateSettingsRepository(application)

    val updateManager = UpdateManager(application, updateSettingsRepository, logBus)

    val scheduleRepository = ScheduleRepository(application)

    val flowRepository = FlowRepository(application)

    val subscriptionRepository = SubscriptionRepository(application)

    val controllerSettingsRepository = ControllerSettingsRepository(application)

    val captureSettingsRepository = CaptureSettingsRepository(application)

    val runLogSink = LogBusRunLogSink(logBus)
}
