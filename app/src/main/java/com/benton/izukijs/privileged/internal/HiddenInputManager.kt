package com.benton.izukijs.privileged.internal

import android.content.Context
import android.view.InputEvent
import java.lang.reflect.Method

internal class HiddenInputManager private constructor(private val manager: Any) {

    private val injectInputEvent: Method? = runCatching {
        manager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
    }.onFailure {
        PrivilegedLog.w("InputManager.injectInputEvent not found: ${it.message}")
    }.getOrNull()

    fun inject(event: InputEvent, mode: Int): Boolean {
        val method = injectInputEvent ?: return false
        return runCatching {
            method.invoke(manager, event, mode) as? Boolean ?: false
        }.onFailure {
            PrivilegedLog.w("injectInputEvent failed: ${it.message}")
        }.getOrDefault(false)
    }

    companion object {
        const val MODE_ASYNC = 0
        const val MODE_WAIT_FOR_FINISH = 2

        fun create(context: Context?): HiddenInputManager? {
            val manager = runCatching {
                context?.getSystemService(Context.INPUT_SERVICE)
            }.getOrNull() ?: runCatching {
                val cls = Class.forName("android.hardware.input.InputManager")
                cls.getMethod("getInstance").invoke(null)
            }.getOrNull() ?: return null
            return HiddenInputManager(manager)
        }
    }
}
