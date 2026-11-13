package com.benton.izukijs.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IInterface
import android.os.IBinder
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.shizuku.ShizukuAuthorization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * 与 Shizuku UserService 的通用连接管理。
 *
 * 解锁与虚拟屏两个客户端原本各有一份几乎相同的连接状态机；抽到这里后只保留
 * 各自的 AIDL 类型与日志标签差异。
 *
 * 连接流程：Shizuku 可用 → bindUserService → 等待 onServiceConnected →
 * 校验 binder 存活并转成 AIDL 接口。[connected] 暴露当前连接状态。
 */
internal class ShizukuUserServiceConnector<T : IInterface>(
    private val args: Shizuku.UserServiceArgs,
    private val label: String,
    private val logBus: LogBus,
    private val adapt: (IBinder) -> T?,
    private val versionOf: (T) -> String,
) {

    private val connectMutex = Mutex()
    private val lock = Any()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var service: T? = null

    @Volatile
    private var pending: ((Boolean) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val resolved = if (binder != null && binder.pingBinder()) {
                runCatching { adapt(binder) }.getOrNull()
            } else {
                null
            }
            service = resolved
            _connected.value = resolved != null
            if (resolved != null) {
                logBus.info("${label}已连接（${runCatching { versionOf(resolved) }.getOrDefault("?")}）")
            } else {
                logBus.warn("${label}返回了无效 Binder")
            }
            complete(resolved != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            invalidate()
            unbind()
            logBus.warn("${label}已断开")
            complete(false)
        }
    }

    fun isAvailable(): Boolean = ShizukuAuthorization.isReady()

    fun isConnected(): Boolean = serviceOrNull() != null

    fun serviceOrNull(): T? {
        val current = service ?: return null
        return if (current.asBinder().pingBinder()) current else {
            invalidate()
            null
        }
    }

    /** 清空当前连接；binder 死亡或调用抛 [android.os.DeadObjectException] 时调用。 */
    fun invalidate() {
        service = null
        _connected.value = false
    }

    suspend fun ensureConnected(timeoutMs: Long = 8_000L): Boolean {
        serviceOrNull()?.let { return true }
        if (!isAvailable()) return false
        return connectMutex.withLock {
            serviceOrNull()?.let { return@withLock true }
            withTimeoutOrNull(timeoutMs) { bindOnce() } ?: run {
                // 超时后 bind 回调仍可能到达；先解绑，避免泄漏 UserService 进程。
                unbind()
                logBus.warn("${label}连接超时")
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
            logBus.warn("绑定${label}失败: ${it.message}")
        }.getOrDefault(false)
        if (!started) {
            synchronized(lock) { pending = null }
            done(false)
        }
        continuation.invokeOnCancellation {
            synchronized(lock) { pending = null }
        }
    }

    private fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun complete(ok: Boolean) {
        val callback = synchronized(lock) {
            val current = pending
            pending = null
            current
        }
        callback?.invoke(ok)
    }
}
