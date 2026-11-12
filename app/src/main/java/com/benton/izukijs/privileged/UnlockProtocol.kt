package com.benton.izukijs.privileged

internal object UnlockProtocol {
    /**
     * 特权服务代码版本。改动 UnlockUserService / privileged.internal 下的代码后 +1，
     * Shizuku 会据此重建仍在运行的旧 UserService。
     */
    const val SERVICE_VERSION = 6
}
