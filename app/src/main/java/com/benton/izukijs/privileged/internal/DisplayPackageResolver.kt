package com.benton.izukijs.privileged.internal

/**
 * 特权进程创建 VirtualDisplay 时，系统会校验传入的 packageName 属于调用 uid。
 * Shizuku UserService 的包上下文仍是 App，但 uid 是 shell 或 root：
 * shell 用 `com.android.shell`；root 没有任何包属于 uid 0，无法通过校验，
 * 必须先把调用身份降到 shell。
 */
internal object DisplayPackageResolver {

    const val ROOT_UID = 0
    const val SHELL_UID = 2000
    const val SYSTEM_UID = 1000
    const val SHELL_PACKAGE = "com.android.shell"
    const val SYSTEM_PACKAGE = "android"

    /** 为 [uid] 选择一个属于它的包名；uid 0 且无包时返回 null。 */
    fun resolve(uid: Int, packages: List<String>?, fallback: String): String? {
        val installed = packages.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when (uid) {
            ROOT_UID -> installed.firstOrNull()

            SHELL_UID -> installed.firstOrNull { it == SHELL_PACKAGE }
                ?: installed.firstOrNull()
                ?: SHELL_PACKAGE

            SYSTEM_UID -> installed.firstOrNull { it == SYSTEM_PACKAGE }
                ?: installed.firstOrNull()
                ?: SYSTEM_PACKAGE

            else -> installed.firstOrNull() ?: fallback
        }
    }
}
