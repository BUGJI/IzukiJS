package com.benton.izukijs.flow

import android.content.Context
import com.benton.izukijs.core.storage.AtomicFile
import com.benton.izukijs.schedule.ScheduleConditions
import com.benton.izukijs.schedule.ScheduleEvent
import com.benton.izukijs.schedule.ScheduleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 脚本编排持久化（JSON 文件）。
 */
class FlowRepository(context: Context) {

    private val file = File(context.filesDir, "flows.json")
    private val lock = Any()

    private val _revision = MutableStateFlow(0)

    /** 编排集合的变更信号，供列表页在新增 / 编辑 / 删除后刷新。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun list(): List<ScriptFlow> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getJSONObject(it).toFlow() }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): ScriptFlow? = list().firstOrNull { it.id == id }

    fun upsert(flow: ScriptFlow) = synchronized(lock) {
        write(list().filterNot { it.id == flow.id } + flow)
    }

    fun delete(id: String) = synchronized(lock) {
        write(list().filterNot { it.id == id })
    }

    /** 脚本重命名后同步更新所有步骤里的脚本引用。 */
    fun moveScript(oldId: String, newId: String, newName: String) = synchronized(lock) {
        if (oldId == newId) return@synchronized
        val current = list()
        val next = current.map { flow ->
            if (flow.steps.any { it.scriptId == oldId }) {
                flow.copy(
                    steps = flow.steps.map { step ->
                        if (step.scriptId == oldId) {
                            step.copy(scriptId = newId, scriptName = newName)
                        } else {
                            step
                        }
                    },
                )
            } else {
                flow
            }
        }
        if (next != current) write(next)
    }

    /** 删除脚本时移除所有相关步骤；步骤被清空的编排一并删除。 */
    fun removeScript(scriptId: String) = synchronized(lock) {
        val current = list()
        val next = current.mapNotNull { flow ->
            val remaining = flow.steps.filterNot { it.scriptId == scriptId }
            when {
                remaining.size == flow.steps.size -> flow
                remaining.isEmpty() -> null
                else -> flow.copy(steps = remaining)
            }
        }
        if (next != current) write(next)
    }

    private fun write(list: List<ScriptFlow>) {
        val array = JSONArray()
        list.sortedWith(compareBy({ it.kind == ScheduleKind.EVENT }, { it.triggerAtMillis }))
            .forEach { array.put(it.toJson()) }
        AtomicFile.writeText(file, array.toString())
        _revision.value++
    }

    suspend fun listAsync(): List<ScriptFlow> = withContext(Dispatchers.IO) { list() }

    suspend fun getAsync(id: String): ScriptFlow? = withContext(Dispatchers.IO) { get(id) }

    suspend fun deleteAsync(id: String) = withContext(Dispatchers.IO) { delete(id) }

    suspend fun upsertAsync(flow: ScriptFlow) = withContext(Dispatchers.IO) { upsert(flow) }

    suspend fun moveScriptAsync(oldId: String, newId: String, newName: String) =
        withContext(Dispatchers.IO) { moveScript(oldId, newId, newName) }

    suspend fun removeScriptAsync(scriptId: String) =
        withContext(Dispatchers.IO) { removeScript(scriptId) }

    private fun ScriptFlow.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("triggerAtMillis", triggerAtMillis)
        put("intervalMinutes", intervalMinutes)
        put("cron", cron)
        event?.let { put("event", it.name) }
        if (!conditions.isEmpty) {
            put(
                "conditions",
                JSONObject().apply {
                    put("charging", conditions.charging)
                    put("wifi", conditions.wifi)
                    put("unlocked", conditions.unlocked)
                    put("foregroundPackage", conditions.foregroundPackage)
                },
            )
        }
        put(
            "steps",
            JSONArray().apply {
                steps.forEach { step ->
                    put(
                        JSONObject().apply {
                            put("id", step.id)
                            put("scriptId", step.scriptId)
                            put("scriptName", step.scriptName)
                        },
                    )
                }
            },
        )
        put("order", order.name)
        put("continueOnError", continueOnError)
        put("enabled", enabled)
        put("lastTriggeredAtMillis", lastTriggeredAtMillis)
    }

    private fun JSONObject.toFlow(): ScriptFlow {
        val interval = optLong("intervalMinutes", 0L)
        val kind = optString("kind", "")
            .let { runCatching { ScheduleKind.valueOf(it) }.getOrNull() }
            ?: if (interval > 0) ScheduleKind.INTERVAL else ScheduleKind.TIME
        val event = optString("event", "")
            .let { runCatching { ScheduleEvent.valueOf(it) }.getOrNull() }
        val conditions = optJSONObject("conditions")?.let { obj ->
            ScheduleConditions(
                charging = obj.optBoolean("charging", false),
                wifi = obj.optBoolean("wifi", false),
                unlocked = obj.optBoolean("unlocked", false),
                foregroundPackage = obj.optString("foregroundPackage", ""),
            )
        } ?: ScheduleConditions()
        val steps = optJSONArray("steps")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val scriptId = obj.optString("scriptId", "")
                if (scriptId.isBlank()) return@mapNotNull null
                FlowStep(
                    id = obj.optString("id", "").ifBlank { "$scriptId-$index" },
                    scriptId = scriptId,
                    scriptName = obj.optString("scriptName", scriptId),
                )
            }
        }.orEmpty()
        val order = optString("order", "")
            .let { runCatching { FlowOrder.valueOf(it) }.getOrNull() }
            ?: FlowOrder.SEQUENTIAL
        return ScriptFlow(
            id = getString("id"),
            name = optString("name", getString("id")),
            kind = kind,
            triggerAtMillis = optLong("triggerAtMillis", 0L),
            intervalMinutes = interval,
            cron = optString("cron", ""),
            event = event,
            conditions = conditions,
            steps = steps,
            order = order,
            continueOnError = optBoolean("continueOnError", true),
            enabled = optBoolean("enabled", true),
            lastTriggeredAtMillis = optLong("lastTriggeredAtMillis", 0L),
        )
    }
}
