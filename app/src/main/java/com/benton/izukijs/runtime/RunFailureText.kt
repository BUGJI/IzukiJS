package com.benton.izukijs.runtime

/**
 * 生成能直接展示的失败描述：自身 message 为空时退化为类名，并追加最内层根因。
 *
 * `ExceptionInInitializerError` 这类包装异常的 message 恒为 null，只打 message 会让
 * 脚本日志只剩 `null`，用户无法定位；因此必须沿 cause 链取根因。
 */
internal fun Throwable.describeForLog(): String {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable = this
    var root: Throwable = this
    while (current !in seen) {
        seen += current
        val next = current.cause ?: break
        current = next
        root = next
    }
    val selfText = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    if (root === this) return selfText
    val rootMessage = root.message?.takeIf { it.isNotBlank() }
    val rootText = if (rootMessage != null) {
        "${root.javaClass.simpleName}: $rootMessage"
    } else {
        root.javaClass.simpleName
    }
    return "$selfText ← $rootText"
}
