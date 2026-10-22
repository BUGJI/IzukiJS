package com.benton.izukijs.runtime

import com.quickjs.QuickJS

/**
 * 把 QuickJS 的 `JS_SetInterruptHandler` 暴露给 Kotlin。
 *
 * 仅靠 `sleep` / 原生调用返回后检查退出标志，无法打断 `while (true) {}` 这类纯计算
 * 死循环。这里通过一个很小的 JNI 垫片，在 QuickJS 运行时上注册中断回调：用户点「停止」
 * 或运行超时后，回调会在 QuickJS 的下一次中断轮询（解释器循环内）返回 1，直接终止脚本。
 *
 * 加载失败（例如旧 ABI / 符号缺失）时 [install] 返回 null，调用方退化为原有的检查点停止。
 */
internal class QuickJsInterrupt private constructor(
    private val runtimePtr: Long,
    private val statePtr: Long,
) {

    private val lock = Any()
    private var closed = false

    /** 请求 QuickJS 在下一个中断轮询点终止执行；可重复调用。 */
    fun stop() {
        synchronized(lock) {
            if (closed) return
            QuickJsInterruptNative.stop(statePtr)
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            QuickJsInterruptNative.uninstall(runtimePtr)
            QuickJsInterruptNative.freeState(statePtr)
        }
    }

    companion object {
        /**
         * QuickJS 的 `runtimePtr` 是包内可见字段，已被 R8 规则完整保留。
         * 取不到时返回 null，而不是让脚本运行失败。
         */
        private val runtimePtrField = runCatching {
            QuickJS::class.java.getDeclaredField("runtimePtr").apply { isAccessible = true }
        }.getOrNull()

        fun install(runtime: QuickJS): QuickJsInterrupt? {
            if (!QuickJsInterruptNative.loaded) return null
            val runtimePtr = runCatching { runtimePtrField?.getLong(runtime) }.getOrNull()
                ?.takeIf { it != 0L } ?: return null
            val statePtr = QuickJsInterruptNative.createState()
            if (statePtr == 0L) return null
            if (!QuickJsInterruptNative.install(runtimePtr, statePtr)) {
                QuickJsInterruptNative.freeState(statePtr)
                return null
            }
            return QuickJsInterrupt(runtimePtr, statePtr)
        }
    }
}

/** JNI 入口。方法名与 `app/src/main/cpp/izuki_quickjs_shim.cpp` 一一对应。 */
internal object QuickJsInterruptNative {

    val loaded: Boolean = runCatching {
        System.loadLibrary("izuki_qjs_shim")
    }.isSuccess

    external fun createState(): Long

    external fun install(runtimePtr: Long, statePtr: Long): Boolean

    external fun stop(statePtr: Long)

    external fun uninstall(runtimePtr: Long)

    external fun freeState(statePtr: Long)
}
