package com.benton.izukijs.developer

/**
 * 全局只读快照：让 object 单例、Service 与原本的 `const val` 调用点无需改构造签名即可读取开发者参数。
 *
 * 只由 [com.benton.izukijs.di.AppContainer] 在收集 [DeveloperSettingsRepository] 时写入；
 * 业务代码只读 [current]，不要直接赋值。
 */
object DeveloperTuning {

    @Volatile
    var current: DeveloperSettings = DeveloperSettings()
        private set

    fun update(value: DeveloperSettings) {
        current = value.normalized()
    }
}
