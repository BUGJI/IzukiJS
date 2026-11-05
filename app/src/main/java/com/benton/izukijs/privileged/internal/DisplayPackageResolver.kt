package com.benton.izukijs.privileged.internal

/**
 * 特权进程创建 VirtualDisplay 时，系统会校验传入的 packageName 属于调用 uid。
 * Shizuku UserService 的包上下文仍是 App，但 uid 是 shell，因此需要按 uid 取对应包名。
 */
internal object DisplayPackageResolver {

    const val SHELL_UID = 2000
    const val SYSTEM_UID = 1000
    const val SHELL_PACKAGE = "com.android.shell"
    const val SYSTEM_PACKAGE = "android"

    fun resolve(uid: Int, packages: List<String>?, fallback: String): String {
        val installed = packages.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return when (uid) {
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
