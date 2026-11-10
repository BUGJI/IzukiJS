package com.benton.izukijs.runtime

import com.benton.izukijs.model.RunMode
import com.benton.izukijs.virtualscreen.VirtualScreenSettings

/** 解析本次脚本的运行模式，并在后台虚拟屏不可用时回退前台。 */
internal object RunModeResolver {

    fun resolve(
        requested: RunMode?,
        settings: VirtualScreenSettings,
        virtualScreenAvailable: Boolean,
        onFallback: () -> Unit = {},
    ): RunMode {
        val effective = requested ?: settings.defaultMode
        if (effective == RunMode.BACKGROUND &&
            (!settings.backgroundAllowed || !virtualScreenAvailable)
        ) {
            onFallback()
            return RunMode.FOREGROUND
        }
        return effective
    }
}
