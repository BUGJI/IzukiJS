package com.benton.izukijs.privileged

import android.content.ComponentName
import android.content.Context
import android.os.DeadObjectException
import com.benton.izukijs.BuildConfig
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.data.UnlockSettings
import com.benton.izukijs.model.UnlockMethod
import com.benton.izukijs.model.UnlockPattern
import com.benton.izukijs.model.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * App 侧到 Shizuku UserService 的连接。
 *
 * 只有 Shizuku 可用时才会尝试；连不上时调用方回退旧的 Shell 实现。
 */
class PrivilegedUnlockClient(
    context: Context,
    private val logBus: LogBus,
) {

    private val connector = ShizukuUserServiceConnector<IUnlockService>(
        args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, UnlockUserService::class.java.name),
        )
            .daemon(true)
            .processNameSuffix("unlock")
            .debuggable(BuildConfig.DEBUG)
            .version(UnlockProtocol.SERVICE_VERSION),
        label = "解锁特权服务",
        logBus = logBus,
        adapt = { binder -> IUnlockService.Stub.asInterface(binder) },
        versionOf = { it.version() },
    )

    fun isShizukuAvailable(): Boolean = connector.isAvailable()

    fun isConnected(): Boolean = connector.isConnected()

    suspend fun ensureConnected(timeoutMs: Long = 8_000L): Boolean =
        connector.ensureConnected(timeoutMs)

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
                connector.serviceOrNull()?.startGestureRecord(timeoutMs)
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
        val current = connector.serviceOrNull() ?: return
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
            runCatching { connector.serviceOrNull()?.isScreenOn() }.getOrNull()
        }
    }

    private suspend fun callString(block: (IUnlockService) -> String?): String? =
        withContext(Dispatchers.IO) {
            val current = connector.serviceOrNull() ?: return@withContext null
            runCatching { block(current) }.onFailure {
                logBus.warn("解锁特权服务调用失败: ${it.message}")
                if (it is DeadObjectException) connector.invalidate()
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
