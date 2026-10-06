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

    private fun write(list: List<Schedule>) {
        val array = JSONArray()
        list.sortedBy { it.triggerAtMillis }.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }

    /** 后台线程版本，避免磁盘 IO 阻塞主线程 / 组合。 */
    suspend fun listAsync(): List<Schedule> = withContext(Dispatchers.IO) { list() }

    suspend fun deleteAsync(id: String) = withContext(Dispatchers.IO) { delete(id) }

    private fun Schedule.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("scriptId", scriptId)
        put("scriptName", scriptName)
        put("triggerAtMillis", triggerAtMillis)
        put("intervalMinutes", intervalMinutes)
    }

    private fun JSONObject.toSchedule(): Schedule = Schedule(
        id = getString("id"),
        scriptId = getString("scriptId"),
        scriptName = getString("scriptName"),
        triggerAtMillis = getLong("triggerAtMillis"),
        intervalMinutes = optLong("intervalMinutes", 0L),
    )
}
