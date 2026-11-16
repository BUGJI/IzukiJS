package com.benton.izukijs.runtime

import com.benton.izukijs.model.RunMode

/**
 * 单次运行的显式覆盖项，主要供 MCP `run_script` 使用。
 *
 * null 表示沿用设置 / 脚本自身解析结果；非 null 时优先于对应设置，便于调用方在
 * 不修改全局配置的情况下避免唤醒屏幕、自动解锁或接管已有虚拟屏。
 */
data class RunOverrides(
    /** 是否点亮屏幕；null 跟随「同时点亮屏幕」设置。 */
    val wakeScreen: Boolean? = null,
    /** 是否自动解锁；null 跟随解锁开关。 */
    val autoUnlock: Boolean? = null,
    /** 虚拟屏已运行时是否复用而不是接管 / 重建；MCP run_script 默认开启，其他调用保持旧行为。 */
    val reuseVirtualScreen: Boolean = false,
    /** 运行模式覆盖；null 跟随脚本 @env / 默认运行模式。 */
    val runMode: RunMode? = null,
)
