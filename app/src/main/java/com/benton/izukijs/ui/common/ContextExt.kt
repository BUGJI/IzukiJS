package com.benton.izukijs.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** 从 Compose 的 [LocalContext] 向上找到宿主 Activity，用于重建以应用语言切换等配置变更。 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
