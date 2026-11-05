package com.benton.izukijs.privileged

internal object VirtualScreenProtocol {
    /**
     * 特权服务代码版本。改动 VirtualScreenUserService / privileged.internal 下与虚拟屏相关的
     * 代码后 +1，Shizuku 会据此重建仍在运行的旧 UserService。
     */
    const val SERVICE_VERSION = 4
}
