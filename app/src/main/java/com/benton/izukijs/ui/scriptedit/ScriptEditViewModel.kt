package com.benton.izukijs.ui.scriptedit

import androidx.lifecycle.ViewModel
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.subscription.ScriptSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 保存脚本编辑页时需要写入的全部字段。 */
data class ScriptEditSaveRequest(
    val current: ScriptInfo,
    val newName: String,
    val useGlobalEnv: Boolean,
    val env: Map<String, String>,
    val envText: String,
    val url: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
    val hadSubscription: Boolean,
)

sealed interface ScriptEditSaveResult {
    data class Saved(
        val script: ScriptInfo,
        /** 非空表示发生了改名，调用方需要把当前路由的 id 切到新值。 */
        val renamedId: String?,
        val subscription: ScriptSubscription?,
        val subscriptionDeleted: Boolean,
        val shouldSync: Boolean,
    ) : ScriptEditSaveResult

    data class Failed(val message: String?) : ScriptEditSaveResult
}

/**
 * 脚本编辑页的保存流程。
 *
 * 改名会牵动环境变量、标签、置顶、磁贴、定时、流程、订阅与运行历史等仓库；
 * 这些都是同步文件 / 偏好写入，必须离开主线程。页面只负责表单与提示。
 */
class ScriptEditViewModel(private val container: AppContainer) : ViewModel() {

    suspend fun save(request: ScriptEditSaveRequest): ScriptEditSaveResult =
        withContext(Dispatchers.IO) {
            runCatching {
                var target = request.current
                var renamedId: String? = null
                if (request.newName != request.current.name) {
                    target = container.scriptRepository.renameAsync(request.current, request.newName)
                    container.scriptEnvRepository.move(request.current.id, target.id)
                    container.scriptListPreferences.moveTags(request.current.id, target.id)
                    container.scriptListPreferences.movePinned(request.current.id, target.id)
                    container.tileSettingsRepository.moveScriptId(request.current.id, target.id)
                    container.scheduleRepository.moveScriptAsync(
                        request.current.id,
                        target.id,
                        target.name,
                    )
                    container.flowRepository.moveScriptAsync(
                        request.current.id,
                        target.id,
                        target.name,
                    )
                    container.subscriptionManager.moveAsync(request.current.id, target.id)
                    container.runHistoryRepository.move(
                        request.current.id,
                        target.id,
                        target.name,
                    )
                    renamedId = target.id
                }

                container.scriptEnvRepository.saveEnvSettings(
                    target.id,
                    request.useGlobalEnv,
                    request.env,
                    request.envText,
                )

                var subscription = container.subscriptionRepository.getAsync(target.id)
                var deleted = false
                var shouldSync = false
                if (request.url.isEmpty()) {
                    if (request.hadSubscription) {
                        container.subscriptionManager.cancelAsync(target.id)
                        subscription = null
                        deleted = true
                    }
                } else {
                    val existing = container.subscriptionRepository.getAsync(target.id)
                    val saved = ScriptSubscription(
                        scriptId = target.id,
                        url = request.url,
                        intervalMinutes = request.intervalMinutes,
                        enabled = request.enabled,
                        lastSyncAtMillis = existing?.lastSyncAtMillis ?: 0L,
                        nextSyncAtMillis = existing?.nextSyncAtMillis ?: 0L,
                        lastError = existing?.lastError.orEmpty(),
                        contentHash = existing?.contentHash.orEmpty(),
                        etag = existing?.etag.orEmpty(),
                        lastModified = existing?.lastModified.orEmpty(),
                    )
                    container.subscriptionManager.scheduleAsync(saved)
                    subscription = container.subscriptionRepository.getAsync(target.id)
                    shouldSync =
                        (existing == null || existing.url != request.url) && request.enabled
                }

                ScriptEditSaveResult.Saved(
                    script = target,
                    renamedId = renamedId,
                    subscription = subscription,
                    subscriptionDeleted = deleted,
                    shouldSync = shouldSync,
                )
            }.getOrElse { error ->
                ScriptEditSaveResult.Failed(error.message)
            }
        }

    suspend fun deleteSubscription(scriptId: String) = withContext(Dispatchers.IO) {
        // 取消失败不应把异常抛回 Compose 协程；页面仍会清理本地表单状态。
        runCatching { container.subscriptionManager.cancelAsync(scriptId) }
    }
}
