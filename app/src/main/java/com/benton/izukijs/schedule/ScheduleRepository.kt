package com.benton.izukijs.schedule

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 定时任务持久化（JSON 文件）。
 */
class ScheduleRepository(context: Context) {

    private val file = File(context.filesDir, "schedules.json")
    private val lock = Any()

    fun list(): List<Schedule> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { array.getJSONObject(it).toSchedule() }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Schedule? = list().firstOrNull { it.id == id }

    fun upsert(schedule: Schedule) = synchronized(lock) {
        write(list().filterNot { it.id == schedule.id } + schedule)
    }

    fun delete(id: String) = synchronized(lock) {
        write(list().filterNot { it.id == id })
    }

    fun moveScript(oldId: String, newId: String, newName: String) = synchronized(lock) {
        if (oldId == newId) return@synchronized
        val current = list()
        val next = current.map { schedule ->
            if (schedule.scriptId == oldId) {
                schedule.copy(scriptId = newId, scriptName = newName)
            } else {
                schedule
            }
        }
        if (next != current) write(next)
    }

    private fun write(list: List<Schedule>) {
        val array = JSONArray()
        list.sortedWith(compareBy({ it.kind == ScheduleKind.EVENT }, { it.triggerAtMillis }))
            .forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }

    /** 后台线程版本，避免磁盘 IO 阻塞主线程 / 组合。 */
    suspend fun listAsync(): List<Schedule> = withContext(Dispatchers.IO) { list() }

    suspend fun deleteAsync(id: String) = withContext(Dispatchers.IO) { delete(id) }

    suspend fun moveScriptAsync(oldId: String, newId: String, newName: String) =
        withContext(Dispatchers.IO) { moveScript(oldId, newId, newName) }

    private fun Schedule.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("scriptId", scriptId)
        put("scriptName", scriptName)
        put("triggerAtMillis", triggerAtMillis)
        put("intervalMinutes", intervalMinutes)
        put("kind", kind.name)
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
        put("retryCount", retryCount)
        put("retryDelayMinutes", retryDelayMinutes)
        put("retryAttempt", retryAttempt)
        put("lastTriggeredAtMillis", lastTriggeredAtMillis)
    }

    private fun JSONObject.toSchedule(): Schedule {
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
        return Schedule(
            id = getString("id"),
            scriptId = getString("scriptId"),
            scriptName = getString("scriptName"),
            triggerAtMillis = optLong("triggerAtMillis", 0L),
            intervalMinutes = interval,
            kind = kind,
            cron = optString("cron", ""),
            event = event,
            conditions = conditions,
            retryCount = optInt("retryCount", 0).coerceAtLeast(0),
            retryDelayMinutes = optLong("retryDelayMinutes", 0L).coerceAtLeast(0L),
            retryAttempt = optInt("retryAttempt", 0).coerceAtLeast(0),
            lastTriggeredAtMillis = optLong("lastTriggeredAtMillis", 0L),
        )
    }
}
