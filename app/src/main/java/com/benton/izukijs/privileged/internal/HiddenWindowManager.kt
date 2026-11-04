package com.benton.izukijs.privileged.internal

import android.os.Bundle
import android.os.IInterface
import java.lang.reflect.Method

internal class HiddenWindowManager private constructor(private val manager: IInterface) {

    private var isKeyguardLockedMethod: Method? = null
    private var isKeyguardSecureMethod: Method? = null
    private var isKeyguardSecureVariant = -1
    private var dismissKeyguardMethod: Method? = null
    private var lockNowMethod: Method? = null
    private var lockNowVariant = -1
    private var setDisplayImePolicyMethod: Method? = null
    private var freezeRotationMethod: Method? = null
    private var setForcedDisplaySizeMethod: Method? = null

    /** null 表示反射不可用，调用方不要把它当成「未锁屏」。 */
    fun isKeyguardLocked(): Boolean? = runCatching {
        val method = isKeyguardLockedMethod ?: manager.javaClass
            .getMethod("isKeyguardLocked")
            .also { isKeyguardLockedMethod = it }
        method.invoke(manager) as? Boolean
    }.onFailure {
        PrivilegedLog.w("isKeyguardLocked failed: ${it.message}")
    }.getOrNull()

    fun isKeyguardSecure(userId: Int = 0): Boolean? = runCatching {
        val method = isKeyguardSecureMethod ?: run {
            val found = runCatching {
                manager.javaClass.getMethod("isKeyguardSecure", Int::class.javaPrimitiveType)
                    .also { isKeyguardSecureVariant = 0 }
            }.getOrElse {
                manager.javaClass.getMethod("isKeyguardSecure").also { isKeyguardSecureVariant = 1 }
            }
            isKeyguardSecureMethod = found
            found
        }
        val value = if (isKeyguardSecureVariant == 0) {
            method.invoke(manager, userId)
        } else {
            method.invoke(manager)
        }
        value as? Boolean
    }.onFailure {
        PrivilegedLog.w("isKeyguardSecure failed: ${it.message}")
    }.getOrNull()

    fun dismissKeyguard(): Boolean = runCatching {
        val method = dismissKeyguardMethod ?: run {
            val callback = Class.forName("com.android.internal.policy.IKeyguardDismissCallback")
            manager.javaClass.getMethod("dismissKeyguard", callback, CharSequence::class.java)
                .also { dismissKeyguardMethod = it }
        }
        method.invoke(manager, null, null)
        true
    }.onFailure {
        PrivilegedLog.w("dismissKeyguard failed: ${it.message}")
    }.getOrDefault(false)

    fun lockNow(): Boolean = runCatching {
        val method = lockNowMethod ?: run {
            val found = runCatching {
                manager.javaClass.getMethod("lockNow", Bundle::class.java)
                    .also { lockNowVariant = 0 }
            }.getOrElse {
                manager.javaClass.getMethod("lockNow").also { lockNowVariant = 1 }
            }
            lockNowMethod = found
            found
        }
        if (lockNowVariant == 0) {
            method.invoke(manager, Bundle())
        } else {
            method.invoke(manager)
        }
        true
    }.onFailure {
        PrivilegedLog.w("lockNow failed: ${it.message}")
    }.getOrDefault(false)

    fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean = runCatching {
        val method = setDisplayImePolicyMethod ?: manager.javaClass
            .getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .also { setDisplayImePolicyMethod = it }
        method.invoke(manager, displayId, policy)
        true
    }.onFailure {
        PrivilegedLog.w("setDisplayImePolicy failed: ${it.message}")
    }.getOrDefault(false)

    fun freezeRotation(displayId: Int, rotation: Int): Boolean = runCatching {
        val method = freezeRotationMethod ?: manager.javaClass
            .getMethod(
                "freezeRotation",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .also { freezeRotationMethod = it }
        method.invoke(manager, displayId, rotation)
        true
    }.onFailure {
        PrivilegedLog.w("freezeRotation failed: ${it.message}")
    }.getOrDefault(false)

    fun setForcedDisplaySize(displayId: Int, width: Int, height: Int): Boolean = runCatching {
        val method = setForcedDisplaySizeMethod ?: manager.javaClass
            .getMethod(
                "setForcedDisplaySize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            .also { setForcedDisplaySizeMethod = it }
        method.invoke(manager, displayId, width, height)
        true
    }.onFailure {
        PrivilegedLog.w("setForcedDisplaySize failed: ${it.message}")
    }.getOrDefault(false)

    companion object {
        fun create(): HiddenWindowManager? {
            val service = HiddenServiceManager.getService("window", "android.view.IWindowManager")
                ?: return null
            return HiddenWindowManager(service)
        }
    }
}
