package com.benton.izukijs.core.app

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 当前前台 Activity 的弱引用。
 *
 * 脚本 API 拿到的是 Application Context，不能直接 `findActivity()`；对话框等需要
 * Activity 的兼容能力通过这里取当前实例，避免把 UI 依赖反向注入 runtime。
 */
object CurrentActivity {

    @Volatile
    private var reference: WeakReference<Activity>? = null

    fun set(activity: Activity) {
        reference = WeakReference(activity)
    }

    fun clear(activity: Activity) {
        if (reference?.get() === activity) reference = null
    }

    fun get(): Activity? = reference?.get()
}
