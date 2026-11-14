package com.benton.izukijs.privileged

internal object VirtualScreenProtocol {
    /**
     * 特权服务代码版本。改动 VirtualScreenUserService / privileged.internal 下与虚拟屏相关的
     * 代码后 +1，Shizuku 会据此重建仍在运行的旧 UserService。
     */
    const val SERVICE_VERSION = 5

    /**
     * lastError() 的身份错误前缀；后面跟稳定的 ASCII 错误码（可带 `|` 分隔的细节）。
     * App 侧据此换成可操作的本地化提示，而不是直接展示底层 SecurityException。
     */
    const val ERROR_IDENTITY_PREFIX = "izuki_identity:"

    /** DisplayManagerService 在 packageName 不属于调用 uid 时抛出的固定文案。 */
    const val PACKAGE_UID_MISMATCH = "packageName must match the calling uid"

    /** 身份切换失败码；与 App 侧的本地化文案一一对应。 */
    object IdentityError {
        const val MAIN_LOOPER_UNAVAILABLE = "main_looper_unavailable"
        const val POST_FAILED = "post_failed"
        const val MAIN_DROP_TIMEOUT = "main_drop_timeout"
        const val SETUID_FAILED = "setuid_failed"
        const val UID_UNCHANGED = "uid_unchanged"
    }
}
