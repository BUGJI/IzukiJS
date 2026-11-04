package com.benton.izukijs.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.data.UnlockSettings
import com.benton.izukijs.model.UnlockMethod
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.service.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * App 侧到 Shizuku UserService 的连接。
 *
 * 只有 Shizuku 可用时才会尝试；连不上时调用方回退旧的 Shell 实现。
 */
class PrivilegedUnlockClient(
    private val context: Context,
    private val logBus: LogBus,
) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, UnlockUserService::class.java.name),
    )
        .daemon(true)
        .processNameSuffix("unlock")
        .debuggable(BuildConfig.DEBUG)
        .version(UnlockProtocol.SERVICE_VERSION)

    private val connectMutex = Mutex()
    private val lock = Any()

    @Volatile
    private var service: IUnlockService? = null

    @Volatile
    private var pending: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = if (binder != null && binder.pingBinder()) {
                runCatching { IUnlockService.Stub.asInterface(binder) }.getOrNull()
            } else {
                null
            }
            if (resolved != null) {
                service = resolved
                logBus.info("解锁特权服务已连接（${runCatching { resolved.version() }.getOrDefault("?")}）")
            } else {
                logBus.warn("解锁特权服务返回了无效 Binder")
            }
            complete(resolved != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            logBus.warn("解锁特权服务已断开")
            complete(false)
        }
    }

    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isConnected(): Boolean = serviceOrNull() != null

    private fun serviceOrNull(): IUnlockService? {
        val current = service ?: return null
        return if (current.asBinder().pingBinder()) current else {
            service = null
            null
        }
    }

    suspend fun ensureConnected(timeoutMs: Long = 8_000L): Boolean {
        serviceOrNull()?.let { return true }
        if (!isShizukuAvailable()) return false
        return connectMutex.withLock {
            serviceOrNull()?.let { return@withLock true }
            withTimeoutOrNull(timeoutMs) { bindOnce() } ?: run {
                logBus.warn("解锁特权服务连接超时")
                false
            }
        }
    }

    private suspend fun bindOnce(): Boolean = suspendCancellableCoroutine { continuation ->
        val done = { ok: Boolean ->
            if (continuation.isActive) continuation.resume(ok)
        }
        synchronized(lock) { pending = done }
        val started = runCatching {
            Shizuku.bindUserService(args, connection)
            true
        }.onFailure {
            logBus.warn("绑定解锁特权服务失败: ${it.message}")
        }.getOrDefault(false)
        if (!started) {
            synchronized(lock) { pending = null }
            done(false)
        }
        continuation.invokeOnCancellation {
            synchronized(lock) { pending = null }
        }
    }

    private fun complete(ok: Boolean) {
        val callback = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        callback?.invoke(ok)
    }

    suspend fun unlock(settings: UnlockSettings): UnlockResult? {
        if (!ensureConnected()) return null
        return when (settings.method) {
            UnlockMethod.SWIPE -> callString { it.unlock("") }
            UnlockMethod.PIN -> callString { it.unlock(settings.pin()) }
            UnlockMethod.PATTERN -> {
                val pattern = settings.pattern() ?: return UnlockResult.CREDENTIAL_REQUIRED
                // 旧 Shell 录制的数据可能只包含图案、不包含上滑，交给旧路径处理。
                if (pattern.screenWidth <= 0) return null
                callString { it.unlockWithGesture(pattern.toJson()) }
            }
        }?.let(::parseResult)
    }

    suspend fun testUnlock(settings: UnlockSettings): UnlockResult? {
        if (!ensureConnected()) return null
        return when (settings.method) {
            UnlockMethod.SWIPE -> callString { it.testUnlock("") }
            UnlockMethod.PIN -> callString { it.testUnlock(settings.pin()) }
            UnlockMethod.PATTERN -> {
                val pattern = settings.pattern() ?: return UnlockResult.CREDENTIAL_REQUIRED
                if (pattern.screenWidth <= 0) return null
                callString { it.testUnlockGesture(pattern.toJson()) }
            }
        }?.let(::parseResult)
    }

    suspend fun startGestureRecord(timeoutMs: Int): Boolean {
        if (!ensureConnected()) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                serviceOrNull()?.startGestureRecord(timeoutMs)
                true
            }.onFailure {
                logBus.warn("启动特权录制失败: ${it.message}")
            }.getOrDefault(false)
        }
    }

    suspend fun pollGestureRecord(): PrivilegedGestureRecord? {
        val raw = callString { it.pollGestureRecord() } ?: return null
        return runCatching { PrivilegedGestureRecord.parse(raw) }.getOrNull()
    }

    /** 取消是 UI/Service 线程发起的，不能阻塞；oneway 调用交给后台线程即可。 */
    fun cancelGestureRecord() {
        val current = serviceOrNull() ?: return
        Thread {
            runCatching { current.cancelGestureRecord() }
        }.apply {
            isDaemon = true
            start()
        }
    }

    suspend fun isScreenOn(): Boolean? {
        if (!ensureConnected()) return null
        return withContext(Dispatchers.IO) {
            runCatching { serviceOrNull()?.isScreenOn() }.getOrNull()
        }
    }

    private suspend fun callString(block: (IUnlockService) -> String?): String? =
        withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext null
            runCatching { block(current) }.onFailure {
                logBus.warn("解锁特权服务调用失败: ${it.message}")
                if (it is android.os.DeadObjectException) service = null
            }.getOrNull()
        }

    private fun parseResult(raw: String): UnlockResult =
        UnlockResult.entries.firstOrNull { it.name == raw } ?: UnlockResult.UNSUPPORTED

}

/** 特权侧录制状态快照。 */
data class PrivilegedGestureRecord(
    val status: String,
    val error: UnlockResult? = null,
    val pattern: UnlockPattern? = null,
    val detail: String? = null,
) {
    val isTerminal: Boolean get() = status == "DONE" || status == "FAILED"

    companion object {
        fun parse(raw: String): PrivilegedGestureRecord {
            val json = JSONObject(raw)
            val status = json.optString("status", "IDLE")
            val error = json.optString("error").takeIf { it.isNotBlank() }
                ?.let { name -> UnlockResult.entries.firstOrNull { it.name == name } }
            val pattern = json.optString("pattern").takeIf { it.isNotBlank() }
                ?.let { UnlockPattern.parseOrNull(it) }
            return PrivilegedGestureRecord(
                status = status,
                error = error,
                pattern = pattern,
                detail = json.optString("detail").takeIf { it.isNotBlank() },
            )
        }
    }
}
