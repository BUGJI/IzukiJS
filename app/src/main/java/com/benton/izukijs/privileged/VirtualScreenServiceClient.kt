package com.benton.izukijs.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.Surface
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * App 侧到虚拟屏 Shizuku UserService 的连接。
 *
 * 非 daemon 模式：App 进程死亡时服务进程随之结束，不会把虚拟屏和 Surface 泄漏在后台。
 */
class VirtualScreenServiceClient(
    private val context: Context,
    private val logBus: LogBus,
) {

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, VirtualScreenUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("virtualscreen")
        .tag("izuki_virtualscreen")
        .debuggable(BuildConfig.DEBUG)
        .version(VirtualScreenProtocol.SERVICE_VERSION)

    private val connectMutex = Mutex()
    private val lock = Any()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var service: IVirtualScreenService? = null

    @Volatile
    private var pending: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = if (binder != null && binder.pingBinder()) {
                runCatching { IVirtualScreenService.Stub.asInterface(binder) }.getOrNull()
            } else {
                null
            }
            service = resolved
            _connected.value = resolved != null
            if (resolved != null) {
                logBus.info("虚拟屏特权服务已连接（${runCatching { resolved.version() }.getOrDefault("?")}）")
            } else {
                logBus.warn("虚拟屏特权服务返回了无效 Binder")
            }
            complete(resolved != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
            logBus.warn("虚拟屏特权服务已断开")
            complete(false)
        }
    }

    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isConnected(): Boolean = serviceOrNull() != null

    private fun serviceOrNull(): IVirtualScreenService? {
        val current = service ?: return null
        return if (current.asBinder().pingBinder()) current else {
            service = null
            _connected.value = false
            null
        }
    }

    suspend fun ensureConnected(timeoutMs: Long = 8_000L): Boolean {
        serviceOrNull()?.let { return true }
        if (!isShizukuAvailable()) return false
        return connectMutex.withLock {
            serviceOrNull()?.let { return@withLock true }
            withTimeoutOrNull(timeoutMs) { bindOnce() } ?: run {
                logBus.warn("虚拟屏特权服务连接超时")
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
            logBus.warn("绑定虚拟屏特权服务失败: ${it.message}")
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

    suspend fun create(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
    ): Int? {
        if (!ensureConnected()) return null
        return withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext null
            runCatching { current.create(width, height, dpi, surface) }
                .onFailure { onCallFailure("create", it) }
                .getOrNull()
                ?.takeIf { it >= 0 }
        }
    }

    suspend fun launch(intent: Intent, forceStopIfElsewhere: Boolean): Boolean? {
        if (!ensureConnected()) return null
        return withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext null
            runCatching { current.launch(intent, forceStopIfElsewhere) }
                .onFailure { onCallFailure("launch", it) }
                .getOrNull()
        }
    }

    suspend fun destroyDisplay() {
        withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext
            runCatching { current.destroyDisplay() }
                .onFailure { onCallFailure("destroyDisplay", it) }
        }
    }

    suspend fun currentPackage(): String? {
        if (!ensureConnected()) return null
        return withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext null
            runCatching { current.currentPackage() }
                .onFailure { onCallFailure("currentPackage", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun onCallFailure(name: String, throwable: Throwable) {
        logBus.warn("虚拟屏特权服务 $name 失败: ${throwable.message}")
        if (throwable is android.os.DeadObjectException) {
            service = null
            _connected.value = false
        }
    }
}
