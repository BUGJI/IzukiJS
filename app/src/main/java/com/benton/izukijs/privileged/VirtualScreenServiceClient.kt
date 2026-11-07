package com.benton.izukijs.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.DeadObjectException
import android.view.Surface
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.core.log.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * App 侧到虚拟屏 Shizuku UserService 的连接。
 *
 * 非 daemon 模式：App 进程死亡时服务进程随之结束，不会把虚拟屏和 Surface 泄漏在后台。
 */
class VirtualScreenServiceClient(
    context: Context,
    private val logBus: LogBus,
) {

    private val connector = ShizukuUserServiceConnector<IVirtualScreenService>(
        args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, VirtualScreenUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("virtualscreen")
            .tag("izuki_virtualscreen")
            .debuggable(BuildConfig.DEBUG)
            .version(VirtualScreenProtocol.SERVICE_VERSION),
        label = "虚拟屏特权服务",
        logBus = logBus,
        adapt = { binder -> IVirtualScreenService.Stub.asInterface(binder) },
        versionOf = { it.version() },
    )

    val connected: StateFlow<Boolean> = connector.connected

    fun isShizukuAvailable(): Boolean = connector.isAvailable()

    fun isConnected(): Boolean = connector.isConnected()

    suspend fun ensureConnected(timeoutMs: Long = 8_000L): Boolean =
        connector.ensureConnected(timeoutMs)

    private fun serviceOrNull(): IVirtualScreenService? = connector.serviceOrNull()

    suspend fun create(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        showSystemDecorations: Boolean,
    ): Int? {
        if (!ensureConnected()) return null
        return withContext(Dispatchers.IO) {
            val current = serviceOrNull() ?: return@withContext null
            runCatching { current.create(width, height, dpi, surface, showSystemDecorations) }
                .onFailure { onCallFailure("create", it) }
                .getOrNull()
                ?.takeIf { it >= 0 }
        }
    }

    suspend fun lastError(): String? {
        val current = serviceOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { current.lastError() }
                .onFailure { onCallFailure("lastError", it) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
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

    /** 轻量心跳：触发一次 binder 事务，避免 UserService 因长时间空闲被系统回收。 */
    suspend fun ping(): Boolean {
        val current = serviceOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { current.version() }
                .onFailure { onCallFailure("ping", it) }
                .isSuccess
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
        if (throwable is DeadObjectException) connector.invalidate()
    }
}
