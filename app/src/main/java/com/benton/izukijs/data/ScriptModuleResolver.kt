package com.benton.izukijs.data

/**
 * `require()` 的模块解析。`from` 可以是脚本 id、项目 id，或上一个模块的绝对路径；
 * 空字符串表示从当前脚本（[ModuleApi] 会补 rootScriptId）开始。
 */
interface ScriptModuleResolver {
    fun resolve(name: String, from: String): String?
    fun source(name: String, from: String): String?
}
