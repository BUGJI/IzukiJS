package com.benton.izukijs.privileged.internal

/**
 * 虚拟屏创建身份策略（纯逻辑，便于 JVM 单测）。
 *
 * DisplayManagerService 要求 packageName 属于调用 uid；Android 上没有任何已安装包
 * 属于 root(uid 0)，因此 root 身份的 Shizuku 必须先降到 shell(uid 2000) 才能建屏。
 */
internal object DisplayIdentityPolicy {

    fun requiresShellDrop(uid: Int): Boolean = uid == DisplayPackageResolver.ROOT_UID

    fun displayUid(uid: Int): Int =
        if (requiresShellDrop(uid)) DisplayPackageResolver.SHELL_UID else uid
}
