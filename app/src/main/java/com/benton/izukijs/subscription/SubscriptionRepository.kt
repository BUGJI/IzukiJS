package com.benton.izukijs.subscription

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SubscriptionRepository(context: Context) {

    private val file = File(context.filesDir, "subscriptions.json")
    private val lock = Any()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun list(): List<ScriptSubscription> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toSubscription()
            }
        }.getOrDefault(emptyList())
    }

    fun get(scriptId: String): ScriptSubscription? = list().firstOrNull { it.scriptId == scriptId }

    fun upsert(subscription: ScriptSubscription) = synchronized(lock) {
        write(list().filterNot { it.scriptId == subscription.scriptId } + subscription)
        _revision.value++
    }

    fun delete(scriptId: String) = synchronized(lock) {
        val current = list()
        val next = current.filterNot { it.scriptId == scriptId }
        if (next.size != current.size) {
            write(next)
            _revision.value++
        }
    }

    fun move(oldId: String, newId: String) = synchronized(lock) {
        val current = list()
        val target = current.firstOrNull { it.scriptId == oldId } ?: return@synchronized
        if (oldId == newId) return@synchronized
        write(current.filterNot { it.scriptId == oldId || it.scriptId == newId } + target.copy(scriptId = newId))
        _revision.value++
    }

    private fun write(list: List<ScriptSubscription>) {
        val array = JSONArray()
        list.sortedBy { it.scriptId }.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }

    suspend fun listAsync(): List<ScriptSubscription> = withContext(Dispatchers.IO) { list() }

    suspend fun getAsync(scriptId: String): ScriptSubscription? =
        withContext(Dispatchers.IO) { get(scriptId) }

    suspend fun upsertAsync(subscription: ScriptSubscription) =
        withContext(Dispatchers.IO) { upsert(subscription) }

    suspend fun deleteAsync(scriptId: String) = withContext(Dispatchers.IO) { delete(scriptId) }

    suspend fun moveAsync(oldId: String, newId: String) = withContext(Dispatchers.IO) { move(oldId, newId) }

    private fun ScriptSubscription.toJson(): JSONObject = JSONObject().apply {
        put("scriptId", scriptId)
        put("url", url)
        put("intervalMinutes", intervalMinutes)
        put("enabled", enabled)
        put("lastSyncAtMillis", lastSyncAtMillis)
        put("nextSyncAtMillis", nextSyncAtMillis)
        put("lastError", lastError)
        put("contentHash", contentHash)
        put("etag", etag)
        put("lastModified", lastModified)
    }

    private fun JSONObject.toSubscription(): ScriptSubscription? {
        val scriptId = optString("scriptId", "")
        val url = optString("url", "")
        if (scriptId.isBlank() || url.isBlank()) return null
        return ScriptSubscription(
            scriptId = scriptId,
            url = url,
            intervalMinutes = SubscriptionSync.normalizeInterval(
                if (has("intervalMinutes")) optLong("intervalMinutes") else null,
            ),
            enabled = optBoolean("enabled", true),
            lastSyncAtMillis = optLong("lastSyncAtMillis", 0L),
            nextSyncAtMillis = optLong("nextSyncAtMillis", 0L),
            lastError = optString("lastError", ""),
            contentHash = optString("contentHash", ""),
            etag = optString("etag", ""),
            lastModified = optString("lastModified", ""),
        )
    }
}
