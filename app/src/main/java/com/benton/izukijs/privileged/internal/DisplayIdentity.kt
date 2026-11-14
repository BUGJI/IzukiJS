package com.benton.izukijs.privileged.internal

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.system.Os
import com.benton.izukijs.privileged.VirtualScreenProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 身份切换失败：code 用于 App 侧本地化，detail 仅用于日志或未知错误兜底。 */
internal data class IdentityFailure(val code: String, val detail: String? = null) {
    fun encode(): String = buildString {
        append(VirtualScreenProtocol.ERROR_IDENTITY_PREFIX)
        append(code)
        if (!detail.isNullOrBlank()) {
            append('|')
            append(detail)
        }
    }
}

/**
 * 把虚拟屏特权进程的调用身份从 root 降到 shell。
 *
 * Shizuku 以 root 运行时 UserService 的 uid 是 0；DisplayManagerService 会校验
 * `packageName` 属于调用 uid，而 uid 0 没有任何已安装包，直接建屏必然抛
 * `SecurityException: packageName must match the calling uid`。
 *
 * binder 的 sender uid 取自进程 group leader（主线程），所以必须在主线程降权；
 * 同时对当前 binder 线程降权，兼容按调用线程读取 uid 的实现。Linux 的 uid 是线程级
 * 的，主线程降权后新创建的 binder 线程仍可能是 root，因此每次建屏都要检查当前线程。
 */
internal object DisplayIdentity {

    private const val LEADER_DROP_TIMEOUT_MS = 2_000L

    private val lock = Any()

    /** 主线程（group leader）是否已成功降到 shell。 */
    private var leaderDropDone = false
    private var leaderDropFailure: IdentityFailure? = null

    /** 返回 null 表示当前调用可以安全使用 shell 身份；否则返回失败原因。 */
    fun ensureShellForDisplay(): IdentityFailure? = synchronized(lock) {
        if (!DisplayIdentityPolicy.requiresShellDrop(Process.myUid())) return null

        if (!leaderDropDone) {
            val failure = dropLeaderToShell()
            if (failure == null) {
                leaderDropDone = true
                leaderDropFailure = null
            } else {
                leaderDropFailure = failure
            }
        }

        // 主线程降权不影响其它线程的 credentials，当前 binder 线程仍需自己降权。
        dropCurrentThreadToShell()?.let { failure ->
            PrivilegedLog.w("无法从 root 切换到 shell 身份：${failure.describe()}")
            return failure
        }
        if (Process.myUid() == DisplayPackageResolver.ROOT_UID) {
            val failure = leaderDropFailure
                ?: IdentityFailure(VirtualScreenProtocol.IdentityError.SETUID_FAILED)
            PrivilegedLog.w("无法从 root 切换到 shell 身份：${failure.describe()}")
            return failure
        }

        if (leaderDropFailure != null) {
            // 当前线程已降权；若 binder 以 group leader 取 uid，后续调用仍会得到
            // SecurityException，由 create() 转成明确提示。
            PrivilegedLog.w("主线程降权失败，继续尝试按线程身份建屏：${leaderDropFailure?.describe()}")
        }
        PrivilegedLog.i("虚拟屏身份已切换为 shell（线程 uid=${Process.myUid()}）")
        null
    }

    /** 在 group leader（主线程）上降权；返回错误或 null。 */
    private fun dropLeaderToShell(): IdentityFailure? {
        val main = Looper.getMainLooper()
            ?: return IdentityFailure(VirtualScreenProtocol.IdentityError.MAIN_LOOPER_UNAVAILABLE)
        if (Looper.myLooper() == main) return dropToShell()

        var failure: IdentityFailure? = null
        val latch = CountDownLatch(1)
        val posted = runCatching {
            Handler(main).post {
                failure = dropToShell()
                latch.countDown()
            }
        }.getOrDefault(false)
        if (!posted) return IdentityFailure(VirtualScreenProtocol.IdentityError.POST_FAILED)
        return if (latch.await(LEADER_DROP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            failure
        } else {
            IdentityFailure(
                VirtualScreenProtocol.IdentityError.MAIN_DROP_TIMEOUT,
                "$LEADER_DROP_TIMEOUT_MS ms",
            )
        }
    }

    private fun dropCurrentThreadToShell(): IdentityFailure? {
        if (Process.myUid() != DisplayPackageResolver.ROOT_UID) return null
        // 只有在确定当前就是主线程时才跳过；Looper.getMainLooper() 为 null 的无 Looper
        // 进程里不能把 null == null 误判成主线程。
        val main = Looper.getMainLooper()
        if (main != null && Looper.myLooper() == main) return null
        return dropToShell()
    }

    @Suppress("DEPRECATION")
    private fun dropToShell(): IdentityFailure? {
        if (Process.myUid() != DisplayPackageResolver.ROOT_UID) return null
        val gidError = runCatching {
            Os.setgid(DisplayPackageResolver.SHELL_UID)
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        return runCatching {
            Os.setuid(DisplayPackageResolver.SHELL_UID)
            if (Os.getuid() != DisplayPackageResolver.SHELL_UID) {
                IdentityFailure(
                    VirtualScreenProtocol.IdentityError.UID_UNCHANGED,
                    "uid=${Os.getuid()}",
                )
            } else {
                if (gidError != null) {
                    PrivilegedLog.w("setgid(${DisplayPackageResolver.SHELL_UID}) failed: $gidError")
                }
                null
            }
        }.getOrElse {
            IdentityFailure(
                VirtualScreenProtocol.IdentityError.SETUID_FAILED,
                "${it.javaClass.simpleName}: ${it.message ?: "setuid failed"}",
            )
        }
    }

    private fun IdentityFailure.describe(): String =
        if (detail.isNullOrBlank()) code else "$code ($detail)"
}
