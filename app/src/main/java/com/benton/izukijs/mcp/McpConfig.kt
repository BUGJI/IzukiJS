package com.benton.izukijs.mcp

import android.util.Base64
import java.security.SecureRandom

/** MCP 服务监听地址范围。 */
enum class McpBindMode(val displayName: String) {
    /** 监听所有网卡，局域网内其它设备可访问。 */
    LAN("局域网"),

    /** 仅监听回环地址，配合 `adb forward` 使用。 */
    LOCALHOST("仅本机"),
}

/**
 * MCP 服务配置。
 *
 * 安全默认：必须配置 [token] 才能启动；`shell` / 脚本执行默认关闭，需要显式开启。
 * 读屏与控制类工具默认可用（这是本功能的核心用途）。
 */
data class McpConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val bindMode: McpBindMode = McpBindMode.LAN,
    val token: String = "",
    val allowShell: Boolean = false,
    val allowScripts: Boolean = false,
    /** 允许 Shell 执行高危命令（rm -rf /、mkfs、pm uninstall 等），默认关闭。 */
    val allowDangerousShell: Boolean = false,
    /** 禁止启动的应用包名列表；支持结尾 `*` 前缀通配（如 `com.bank.*`）。 */
    val blockedPackages: List<String> = emptyList(),
) {
    val isConfigured: Boolean get() = token.isNotBlank()

    /** 判断某包名是否命中黑名单。 */
    fun isPackageBlocked(packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isBlank()) return false
        return blockedPackages.any { entry ->
            when {
                entry.isBlank() -> false
                entry.endsWith("*") -> target.startsWith(entry.dropLast(1).trim(), ignoreCase = true)
                else -> target.equals(entry.trim(), ignoreCase = true)
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        /** 生成一个新的访问令牌（24 字节随机，Base64 URL 无填充）。 */
        fun newToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}
