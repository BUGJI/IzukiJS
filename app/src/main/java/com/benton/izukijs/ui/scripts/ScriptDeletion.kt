package com.benton.izukijs.ui.scripts

import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.flow.FlowStep
import com.benton.izukijs.flow.ScriptFlow
import com.benton.izukijs.model.ScriptInfo
import com.benton.izukijs.schedule.Schedule
import com.benton.izukijs.subscription.ScriptSubscription

/** 批量删除结果：只包含真正删除成功的脚本，失败项单独返回给 UI。 */
data class ScriptDeletionOutcome(
    val backups: List<ScriptDeletionBackup>,
    val failedNames: List<String>,
)

/** 删除脚本时保留的完整快照，供 Snackbar「撤销」恢复。 */
data class ScriptDeletionBackup(
    val scriptId: String,
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
    val flows: List<ScriptFlow>,
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
    ): ScriptDeletionBackup? = deleteAll(
        scripts = listOf(script),
        tagsOf = { tags },
        pinnedOf = { pinned },
        pinnedToTileId = if (pinnedToTile) script.id else null,
    ).backups.singleOrNull()

    /**
     * 批量删除。先为每个脚本拍快照，再统一清理级联数据：
     * 订阅 / 定时任务取消、编排步骤移除、脚本文件与运行参数清理、标签与置顶回收。
     * 编排只快照一次，保证撤销时能按原始步骤重建。
     *
     * 先删文件、后清级联：文件删除失败的脚本保持原有标签 / 置顶 / 运行参数，
     * 避免列表刷新后又出现一个“空壳”脚本。
     */
    suspend fun deleteAll(
        scripts: List<ScriptInfo>,
        tagsOf: (String) -> List<String>,
        pinnedOf: (String) -> Boolean,
        pinnedToTileId: String?,
    ): ScriptDeletionOutcome {
        if (scripts.isEmpty()) return ScriptDeletionOutcome(emptyList(), emptyList())
        // 编排快照必须在任何移除之前完成；多个脚本可能共享同一条编排。
        val flowSnapshot = container.flowRepository.listAsync()
        val backups = scripts.map { script ->
            ScriptDeletionBackup(
                scriptId = script.id,
                name = script.name,
                content = container.scriptRepository.readAsync(script),
                values = container.scriptEnvRepository.values(script.id),
                state = container.scriptEnvRepository.state(script.id),
                useGlobalEnv = container.scriptEnvRepository.useGlobalEnv(script.id),
                env = container.scriptEnvRepository.env(script.id),
                envText = container.scriptEnvRepository.envText(script.id),
                tags = tagsOf(script.id),
                pinned = pinnedOf(script.id),
                pinnedToTile = script.id == pinnedToTileId,
                subscription = container.subscriptionRepository.getAsync(script.id),
                schedules = container.scheduleRepository.listAsync()
                    .filter { it.scriptId == script.id },
                flows = flowSnapshot,
            )
        }
        val deletedIds = container.scriptRepository.deleteAllAsync(scripts)
        val failedNames = scripts.filterNot { it.id in deletedIds }.map { it.name }
        if (deletedIds.isEmpty()) return ScriptDeletionOutcome(emptyList(), failedNames)
        val deletedBackups = backups.filter { it.scriptId in deletedIds }
        deletedBackups.forEach { container.subscriptionManager.cancelAsync(it.scriptId) }
        deletedBackups.forEach { backup ->
            backup.schedules.forEach { container.scheduleManager.cancelAsync(it.id) }
        }
        // 只包含待删脚本的编排会被清空并删除，需先停止其调度。
        flowSnapshot
            .filter { flow -> shouldCancelFlow(flow, deletedIds) }
            .forEach { flow -> container.flowManager.cancelAsync(flow.id) }
        deletedIds.forEach { id -> container.flowRepository.removeScriptAsync(id) }
        deletedIds.forEach { id -> container.scriptEnvRepository.remove(id) }
        container.scriptListPreferences.clearTags(deletedIds)
        container.scriptListPreferences.setPinnedAll(deletedIds, false)
        if (pinnedToTileId != null && pinnedToTileId in deletedIds) {
            container.tileSettingsRepository.setScriptId(null)
        }
        return ScriptDeletionOutcome(deletedBackups, failedNames)
    }

    suspend fun restore(backup: ScriptDeletionBackup): ScriptInfo =
        restoreAll(listOf(backup)).single()

    /**
     * 批量撤销。脚本会以新 id 重建，因此编排步骤需要一次性按 id 映射回填，
     * 不能逐个 backup 处理：否则共享编排会把尚未重建的旧 id 步骤写回去。
     */
    suspend fun restoreAll(backups: List<ScriptDeletionBackup>): List<ScriptInfo> {
        if (backups.isEmpty()) return emptyList()
        val idMap = LinkedHashMap<String, String>()
        val restoredById = HashMap<String, ScriptInfo>()
        val restored = backups.map { backup ->
            val script = restoreScript(backup)
            idMap[backup.scriptId] = script.id
            restoredById[script.id] = script
            script
        }
        restoreFlows(backups, idMap, restoredById)
        return restored
    }

    private suspend fun restoreScript(backup: ScriptDeletionBackup): ScriptInfo {
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

    private suspend fun restoreFlows(
        backups: List<ScriptDeletionBackup>,
        idMap: Map<String, String>,
        restoredById: Map<String, ScriptInfo>,
    ) {
        if (idMap.isEmpty()) return
        val restoredNames = restoredById.mapValues { it.value.name }
        val originals = backups.flatMap { it.flows }.distinctBy { it.id }
        originals
            .filter { flow -> flow.steps.any { it.scriptId in idMap } }
            .forEach { original ->
                val mappedSteps = remapFlowSteps(original.steps, idMap, restoredNames)
                val current = container.flowRepository.getAsync(original.id)
                if (current == null) {
                    container.flowManager.scheduleAsync(original.copy(steps = mappedSteps))
                } else {
                    val merged = insertRestoredSteps(current.steps, mappedSteps, restoredById.keys)
                    container.flowRepository.upsertAsync(current.copy(steps = merged))
                }
            }
    }
}

/**
 * 删除脚本后，只有“引用了待删脚本且所有步骤都属于待删脚本”的编排会被整条移除。
 * 保留 `any` 判断，避免空步骤编排被 `all` 的真空真值误删。
 */
internal fun shouldCancelFlow(flow: ScriptFlow, deletedIds: Set<String>): Boolean =
    flow.steps.any { it.scriptId in deletedIds } &&
        flow.steps.all { it.scriptId in deletedIds }

/** 把编排步骤里的旧脚本 id / 名称替换为撤销后重建的新 id / 名称。 */
internal fun remapFlowSteps(
    steps: List<FlowStep>,
    idMap: Map<String, String>,
    restoredNames: Map<String, String>,
): List<FlowStep> = steps.map { step ->
    val newId = idMap[step.scriptId] ?: return@map step
    step.copy(
        scriptId = newId,
        scriptName = restoredNames[newId] ?: step.scriptName,
    )
}

/**
 * 把撤销重建的步骤插回仍然存在的编排：按原始位置插入，避免多脚本恢复时顺序错乱；
 * 已存在的步骤不重复插入。
 */
internal fun insertRestoredSteps(
    current: List<FlowStep>,
    mappedSteps: List<FlowStep>,
    restoredIds: Set<String>,
): List<FlowStep> {
    var merged = current
    mappedSteps.forEachIndexed { index, step ->
        // 只补回本次重建的步骤，未受影响的步骤保持现状。
        if (step.scriptId !in restoredIds) return@forEachIndexed
        if (merged.none { it.id == step.id }) {
            val insertAt = index.coerceIn(0, merged.size)
            merged = merged.toMutableList().apply { add(insertAt, step) }
        }
    }
    return merged
}
