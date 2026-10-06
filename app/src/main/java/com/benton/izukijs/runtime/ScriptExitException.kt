package com.benton.izukijs.runtime

/**
 * 脚本请求退出时抛出。用于中断当前 JS 执行。
 */
class ScriptExitException : RuntimeException("IZUKI_SCRIPT_EXIT") {
    companion object {
        const val MARKER = "IZUKI_SCRIPT_EXIT"
    }
}
