package com.benton.izukijs.privileged.internal

import android.os.IBinder
import android.os.IInterface

internal object HiddenServiceManager {

    fun getService(service: String, type: String): IInterface? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, service) as? IBinder ?: return null
        val stub = Class.forName("$type\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder) as? IInterface
    }.onFailure {
        PrivilegedLog.w("getService($service) failed: ${it.message}", it)
    }.getOrNull()
}
