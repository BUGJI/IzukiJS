package com.benton.izukijs.privileged.internal

import android.os.Build
import android.os.IInterface
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal class HiddenPowerManager private constructor(private val manager: IInterface) {

    private var isScreenOnMethod: Method? = null
    private var wakeUpMethod: Method? = null
    private var wakeUpVariant = -1
    private var goToSleepMethod: Method? = null
    private var goToSleepVariant = -1

    fun isScreenOn(displayId: Int = 0): Boolean = runCatching {
        val method = isScreenOnMethod ?: run {
            val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.javaClass.getMethod("isDisplayInteractive", Int::class.javaPrimitiveType)
            } else {
                manager.javaClass.getMethod("isInteractive")
            }
            isScreenOnMethod = found
            found
        }
        val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            method.invoke(manager, displayId)
        } else {
            method.invoke(manager)
        }
        value as? Boolean ?: false
    }.onFailure {
        PrivilegedLog.w("isScreenOn failed: ${it.message}")
    }.getOrDefault(false)

    fun wakeUp(): Boolean {
        val method = runCatching { resolveWakeUp() }.getOrNull() ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            when (wakeUpVariant) {
                0 -> method.invoke(manager, now, WAKE_REASON_APPLICATION, "izuki:wake", PACKAGE_NAME)
                1 -> method.invoke(manager, now, "izuki:wake", PACKAGE_NAME)
                else -> method.invoke(manager, now)
            }
            true
        } catch (e: InvocationTargetException) {
            // 客户端 AppOps 读取失败不代表系统没执行
            PrivilegedLog.w("wakeUp target failed: ${e.cause?.message}")
            true
        } catch (e: ReflectiveOperationException) {
            PrivilegedLog.w("wakeUp failed: ${e.message}", e)
            false
        }
    }

    fun goToSleep(): Boolean {
        val method = runCatching { resolveGoToSleep() }.getOrNull() ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            if (goToSleepVariant == 0) {
                method.invoke(manager, now, GO_TO_SLEEP_REASON_APPLICATION, 0)
            } else {
                method.invoke(manager, now)
            }
            true
        } catch (e: InvocationTargetException) {
            PrivilegedLog.w("goToSleep target failed: ${e.cause?.message}")
            true
        } catch (e: ReflectiveOperationException) {
            PrivilegedLog.w("goToSleep failed: ${e.message}", e)
            false
        }
    }

    private fun resolveWakeUp(): Method {
        wakeUpMethod?.let { return it }
        val cls = manager.javaClass
        val found = runCatching {
            cls.getMethod("wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java, String::class.java)
                .also { wakeUpVariant = 0 }
        }.getOrElse {
            runCatching {
                cls.getMethod("wakeUp", Long::class.javaPrimitiveType, String::class.java, String::class.java)
                    .also { wakeUpVariant = 1 }
            }.getOrElse {
                cls.getMethod("wakeUp", Long::class.javaPrimitiveType).also { wakeUpVariant = 2 }
            }
        }
        wakeUpMethod = found
        return found
    }

    private fun resolveGoToSleep(): Method {
        goToSleepMethod?.let { return it }
        val cls = manager.javaClass
        val found = runCatching {
            cls.getMethod(
                "goToSleep",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).also { goToSleepVariant = 0 }
        }.getOrElse {
            cls.getMethod("goToSleep", Long::class.javaPrimitiveType).also { goToSleepVariant = 1 }
        }
        goToSleepMethod = found
        return found
    }

    companion object {
        private const val WAKE_REASON_APPLICATION = 2
        private const val GO_TO_SLEEP_REASON_APPLICATION = 2
        private const val PACKAGE_NAME = "com.benton.izukijs"

        fun create(): HiddenPowerManager? {
            val service = HiddenServiceManager.getService("power", "android.os.IPowerManager")
                ?: return null
            return HiddenPowerManager(service)
        }
    }
}
