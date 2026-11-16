package com.benton.izukijs.runtime

import android.webkit.JavascriptInterface
import com.benton.izukijs.core.log.LogBus
import com.quickjs.JSArray
import com.quickjs.JSContext
import com.quickjs.JSFunction
import com.quickjs.JSObject
import com.quickjs.JavaCallback
import com.quickjs.JavaVoidCallback
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * QuickJS 的 @JavascriptInterface 注册垫片。
 *
 * QuickJS 1.4.6.1 的 JSObject.getParameters 按「实际实参个数」建数组，却按
 * 「Java 形参类型」逐项取值，多传一个参数就会 ArrayIndexOutOfBounds。这里改为按
 * 形参个数取值：多余实参忽略（单个 String 形参时拼接），缺失实参使用默认值，
 * 避免脚本里的 log("x", 1) 直接把整个运行时打崩。
 */
internal class QuickJsBridge(private val logBus: LogBus? = null) {

    /** 注册到全局作用域（GlobalApi / InputApi）。 */
    fun appendGlobal(context: JSContext, target: Any) {
        register(context, target)
    }

    /** 注册到命名空间，例如 `device`、`ocr`、`notify`。 */
    fun addNamespace(context: JSContext, target: Any, name: String) {
        val namespace = JSObject(context)
        register(namespace, target)
        context.set(name, namespace)
    }

    private fun register(target: JSObject, obj: Any) {
        obj.javaClass.methods.forEach { method ->
            if (method.getAnnotation(JavascriptInterface::class.java) == null) return@forEach
            val name = method.name
            if (method.returnType == Void.TYPE) {
                target.registerJavaMethod(
                    JavaVoidCallback { _, args ->
                        invoke(method, obj, args)
                    },
                    name,
                )
            } else {
                target.registerJavaMethod(
                    JavaCallback { _, args ->
                        invoke(method, obj, args)
                    },
                    name,
                )
            }
        }
    }

    private fun invoke(method: Method, obj: Any, args: JSArray): Any? {
        val types = method.parameterTypes
        val actualCount = args.length()
        if (actualCount != types.size) {
            logBus?.debug(
                "JS 调用 ${method.name} 实参 ${actualCount} 个，方法形参 ${types.size} 个，已自动适配",
            )
        }
        val params = arrayOfNulls<Any?>(types.size)
        for (index in types.indices) {
            params[index] = if (index < actualCount) {
                convert(types[index], args, index)
            } else {
                defaultValue(types[index])
            }
        }
        // 常见写法 log("x", i, y)：单 String 形参时把多余实参拼成一行，而不是丢掉。
        if (types.size == 1 && types[0] == String::class.java && actualCount > 1) {
            params[0] = (0 until actualCount).joinToString(" ") { stringify(args, it) }
        }
        return try {
            method.invoke(obj, *params)
        } catch (t: InvocationTargetException) {
            logBus?.warn("JS 调用 ${method.name} 失败: ${t.targetException.message}")
            null
        } catch (t: Throwable) {
            logBus?.warn("JS 调用 ${method.name} 失败: ${t.message}")
            null
        }
    }

    private fun convert(type: Class<*>, args: JSArray, index: Int): Any? = when {
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> args.getInteger(index)
        type == Double::class.javaPrimitiveType || type == Double::class.javaObjectType -> args.getDouble(index)
        type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> args.getBoolean(index)
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> args.getDouble(index).toLong()
        type == Float::class.javaPrimitiveType || type == Float::class.javaObjectType -> args.getDouble(index).toFloat()
        type == String::class.java -> args.getString(index)
        type == JSArray::class.java -> args.getArray(index)
        type == JSObject::class.java || type == JSFunction::class.java -> args.getObject(index)
        else -> null
    }

    private fun defaultValue(type: Class<*>): Any? = when {
        type == Int::class.javaPrimitiveType -> 0
        type == Double::class.javaPrimitiveType -> 0.0
        type == Boolean::class.javaPrimitiveType -> false
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        else -> null
    }

    private fun stringify(args: JSArray, index: Int): String {
        val asString = args.getString(index)
        if (asString != null) return asString
        return args.get(index)?.toString().orEmpty()
    }
}
