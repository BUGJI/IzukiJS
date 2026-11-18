package com.benton.izukijs.core.config

/**
 * `core` 层需要的运行时限额。
 *
 * 由 `AppContainer` 在开发者设置变化时写入，避免 `core` 反向依赖 `developer` 包；
 * 默认值与 `DeveloperSettings.maxScriptBytes` 保持一致。
 */
object RuntimeLimits {
    @Volatile
    var maxScriptBytes: Int = 1024 * 1024
}
