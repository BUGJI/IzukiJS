package com.benton.izukijs.subscription

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.benton.izukijs.data.ScriptRepository
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SubscriptionManager(
    context: Context,
    private val repository: SubscriptionRepository,
    private val scriptRepository: ScriptRepository,
    private val logBus: LogBus,
) {

    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    fun schedule(subscription: ScriptSubscription) {
        val interval = SubscriptionSync.normalizeInterval(subscription.intervalMinutes)
        val enabled = subscription.enabled && !SubscriptionSync.isDisabled(interval)
        val next = if (enabled) {
            System.currentTimeMillis() + interval * 60_000L
        } else {
            0L
        }
        val updated = subscription.copy(
            url = subscription.url.trim(),
            intervalMinutes = interval,
            enabled = enabled,
            nextSyncAtMillis = next,
        )
        repository.upsert(updated)
        if (enabled) {
            setAlarm(updated.scriptId, next)
            logBus.info("已设置订阅同步: ${updated.scriptId}，每 $interval 分钟")
        } else {
            cancelAlarm(updated.scriptId)
        }
    }

    suspend fun scheduleAsync(subscription: ScriptSubscription) =
        withContext(Dispatchers.IO) { syncMutex.withLock { schedule(subscription) } }

    fun cancel(scriptId: String) {
        cancelAlarm(scriptId)
        repository.delete(scriptId)
    }

    suspend fun cancelAsync(scriptId: String) =
        withContext(Dispatchers.IO) { syncMutex.withLock { cancel(scriptId) } }

    suspend fun moveAsync(oldId: String, newId: String) = withContext(Dispatchers.IO) {
        if (oldId == newId) return@withContext
        syncMutex.withLock {
            cancelAlarm(oldId)
            repository.move(oldId, newId)
            val moved = repository.get(newId) ?: return@withLock
            if (!moved.enabled || SubscriptionSync.isDisabled(moved.intervalMinutes)) {
                return@withLock
            }
            val now = System.currentTimeMillis()
            val at = moved.nextSyncAtMillis.takeIf { it > now } ?: now + 5_000L
            if (at != moved.nextSyncAtMillis) {
                repository.upsert(moved.copy(nextSyncAtMillis = at))
            }
            setAlarm(newId, at)
        }
    }

    fun onAlarm(scriptId: String) {
        scope.launch {
            val subscription = repository.get(scriptId) ?: return@launch
            if (!subscription.enabled) return@launch
            sync(subscription)
        }
    }

    suspend fun syncNow(scriptId: String): Boolean = withContext(Dispatchers.IO) {
        val subscription = repository.get(scriptId) ?: return@withContext false
        sync(subscription)
    }

    fun syncNowAsync(scriptId: String, onFinished: (Boolean) -> Unit = {}) {
        scope.launch {
            val ok = runCatching { syncNow(scriptId) }.getOrDefault(false)
            onFinished(ok)
        }
    }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        repository.list().forEach { subscription ->
            if (!subscription.enabled || SubscriptionSync.isDisabled(subscription.intervalMinutes)) {
                cancelAlarm(subscription.scriptId)
                return@forEach
            }
            val at = subscription.nextSyncAtMillis.takeIf { it > now } ?: now + 5_000L
            if (at != subscription.nextSyncAtMillis) {
                repository.upsert(subscription.copy(nextSyncAtMillis = at))
            }
            setAlarm(subscription.scriptId, at)
        }
    }

    private suspend fun sync(subscription: ScriptSubscription): Boolean = syncMutex.withLock {
        val script = scriptRepository.find(subscription.scriptId)
        if (script == null) {
            logBus.warn("订阅对应的脚本不存在，已移除订阅: ${subscription.scriptId}")
            cancel(subscription.scriptId)
            return@withLock false
        }

        val interval = SubscriptionSync.normalizeInterval(subscription.intervalMinutes)
        if (SubscriptionSync.isDisabled(interval)) {
            repository.upsert(
                subscription.copy(intervalMinutes = 0L, enabled = false, nextSyncAtMillis = 0L),
            )
            cancelAlarm(subscription.scriptId)
            return@withLock false
        }

        val result = fetch(subscription)
        val now = System.currentTimeMillis()
        val next = now + interval * 60_000L
        val base = subscription.copy(intervalMinutes = interval, nextSyncAtMillis = next)

        val updated = when (result) {
            FetchResult.NotModified -> base.copy(lastSyncAtMillis = now, lastError = "")

            is FetchResult.Success -> {
                val local = scriptRepository.read(script)
                if (SubscriptionSync.shouldOverwrite(local, result.body, subscription.contentHash)) {
                    scriptRepository.write(script, result.body)
                    logBus.info("订阅已更新: ${script.name}")
                } else {
                    logBus.info("订阅内容无变化: ${script.name}")
                }
                base.copy(
                    lastSyncAtMillis = now,
                    lastError = "",
                    contentHash = SubscriptionSync.sha256(result.body),
                    etag = result.etag,
                    lastModified = result.lastModified,
                )
            }

            is FetchResult.Failure -> {
                logBus.warn("订阅同步失败 (${script.name}): ${result.message}")
                base.copy(lastError = result.message)
            }
        }

        val enabled = updated.enabled && !SubscriptionSync.isDisabled(updated.intervalMinutes)
        val finalSubscription = if (enabled) {
            updated
        } else {
            updated.copy(enabled = false, nextSyncAtMillis = 0L)
        }
        repository.upsert(finalSubscription)
        if (enabled) {
            setAlarm(finalSubscription.scriptId, next)
        } else {
            cancelAlarm(finalSubscription.scriptId)
        }
        result is FetchResult.Success || result == FetchResult.NotModified
    }

    private suspend fun fetch(subscription: ScriptSubscription): FetchResult =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!SubscriptionSync.isHttpUrl(subscription.url)) {
                    return@runCatching FetchResult.Failure("仅支持 http/https 链接")
                }
                val url = URL(subscription.url)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "Accept",
                        "text/javascript, application/javascript, text/plain, */*",
                    )
                    if (subscription.etag.isNotBlank()) {
                        setRequestProperty("If-None-Match", subscription.etag)
                    }
                    if (subscription.lastModified.isNotBlank()) {
                        setRequestProperty("If-Modified-Since", subscription.lastModified)
                    }
                }
                try {
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                        in 200..299 -> {
                            val body = connection.inputStream.bufferedReader().use { it.readText() }
                            if (body.isBlank()) {
                                FetchResult.Failure("下载内容为空")
                            } else {
                                FetchResult.Success(
                                    body = body,
                                    etag = connection.getHeaderField("ETag").orEmpty(),
                                    lastModified = connection.getHeaderField("Last-Modified").orEmpty(),
                                )
                            }
                        }

                        else -> FetchResult.Failure("HTTP $code")
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                FetchResult.Failure(error.message ?: error.javaClass.simpleName)
            }
        }

    private fun setAlarm(scriptId: String, triggerAt: Long) {
        val pendingIntent = pendingIntent(scriptId)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !alarmManager.canScheduleExactAlarms()
            ) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (t: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun cancelAlarm(scriptId: String) {
        alarmManager.cancel(pendingIntent(scriptId))
    }

    private fun pendingIntent(scriptId: String): PendingIntent {
        val intent = Intent(appContext, SubscriptionReceiver::class.java).apply {
            action = SubscriptionReceiver.ACTION_SYNC
            putExtra(SubscriptionReceiver.EXTRA_ID, scriptId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            scriptId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private sealed interface FetchResult {
        data object NotModified : FetchResult

        data class Success(
            val body: String,
            val etag: String,
            val lastModified: String,
        ) : FetchResult

        data class Failure(val message: String) : FetchResult
    }
}
