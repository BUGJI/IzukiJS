package com.benton.izukijs.ui.scripts

import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.subscription.ScriptSubscription

/** 删除脚本时保留的完整快照，供 Snackbar「撤销」恢复。 */
data class ScriptDeletionBackup(
    val name: String,
    val content: String,
    val values: Map<String, String>,
    val state: Map<String, String>,
    val useGlobalEnv: Boolean,
    val env: Map<String, String>,
    val envText: String,
    val tags: List<String>,
    val pinned: Boolean,
    val pinnedToTile: Boolean,
    val subscription: ScriptSubscription?,
    val schedules: List<Schedule>,
)

/**
 * 脚本删除 / 撤销的级联逻辑。
 *
 * 删除会同时清理订阅、定时任务、运行参数、标签、置顶与磁贴绑定；
 * 撤销时按快照重建脚本并恢复全部关联数据。集中在此便于单测与复用，
 * 也让列表页只负责弹窗与 Snackbar。
 */
class ScriptDeletion(private val container: AppContainer) {

    suspend fun delete(
        script: ScriptInfo,
        tags: List<String>,
        pinned: Boolean,
        pinnedToTile: Boolean,
    ): ScriptDeletionBackup {
        val backup = ScriptDeletionBackup(
            name = script.name,
            content = container.scriptRepository.readAsync(script),
            values = container.scriptEnvRepository.values(script.id),
            state = container.scriptEnvRepository.state(script.id),
            useGlobalEnv = container.scriptEnvRepository.useGlobalEnv(script.id),
            env = container.scriptEnvRepository.env(script.id),
            envText = container.scriptEnvRepository.envText(script.id),
            tags = tags,
            pinned = pinned,
            pinnedToTile = pinnedToTile,
            subscription = container.subscriptionRepository.getAsync(script.id),
            schedules = container.scheduleRepository.listAsync()
                .filter { it.scriptId == script.id },
        )
        container.subscriptionManager.cancelAsync(script.id)
        backup.schedules.forEach { container.scheduleManager.cancelAsync(it.id) }
        container.scriptRepository.deleteAsync(script)
        container.scriptEnvRepository.remove(script.id)
        container.scriptListPreferences.removeTags(script.id)
        container.scriptListPreferences.setPinned(script.id, false)
        if (pinnedToTile) container.tileSettingsRepository.setScriptId(null)
        return backup
    }

    suspend fun restore(backup: ScriptDeletionBackup): ScriptInfo {
        val restored = container.scriptRepository.createAsync(backup.name, backup.content)
        if (backup.tags.isNotEmpty()) {
            container.scriptListPreferences.setTags(restored.id, backup.tags)
        }
        if (backup.values.isNotEmpty()) {
            container.scriptEnvRepository.saveValues(restored.id, backup.values)
        }
        backup.state.forEach { (key, value) ->
            container.scriptEnvRepository.putState(restored.id, key, value)
        }
        container.scriptEnvRepository.saveEnvSettings(
            restored.id,
            backup.useGlobalEnv,
            backup.env,
            backup.envText,
        )
        if (backup.pinned) container.scriptListPreferences.setPinned(restored.id, true)
        if (backup.pinnedToTile) container.tileSettingsRepository.setScriptId(restored.id)
        backup.subscription?.let {
            container.subscriptionManager.scheduleAsync(it.copy(scriptId = restored.id))
        }
        backup.schedules.forEach { schedule ->
            container.scheduleManager.scheduleAsync(
                schedule.copy(scriptId = restored.id, scriptName = restored.name),
            )
        }
        return restored
    }
}
