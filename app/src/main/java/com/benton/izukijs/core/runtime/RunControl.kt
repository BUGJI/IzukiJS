package com.benton.izukijs.core.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * 运行中脚本的最小控制面。
 *
 * 通知栏、悬浮窗等 UI 组件只需要「是否在跑 / 脚本名 / 请求停止」，
 * 依赖此接口即可避免反向依赖 runtime 的具体实现。
 */
interface RunControl {
    val running: StateFlow<Boolean>
    val runningScript: StateFlow<String?>
    fun requestStop()
}
