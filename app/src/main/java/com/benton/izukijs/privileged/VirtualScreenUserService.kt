package com.benton.izukijs.privileged

import android.content.Context
import android.content.Intent
import android.os.Process
import android.view.Surface
import androidx.annotation.Keep
import com.benton.izukijs.privileged.internal.PrivilegedLog
import com.benton.izukijs.privileged.internal.PrivilegedVirtualDisplay
import com.benton.izukijs.privileged.internal.SystemContext
import kotlin.system.exitProcess

/**
 * 后台虚拟屏的 Shizuku UserService 入口。
 *
 * 只负责创建/销毁 VirtualDisplay 与拉起目标应用；画面采集和 PiP 预览都在 App 进程完成。
 */
@Keep
class VirtualScreenUserService : IVirtualScreenService.Stub {

    private var serviceContext: Context? = null
    private var display: PrivilegedVirtualDisplay? = null

    constructor() {
        serviceContext = SystemContext.get()
    }

    @Keep
    constructor(context: Context) {
        serviceContext = context
    }

    override fun version(): String =
        "izuki-virtualscreen-${VirtualScreenProtocol.SERVICE_VERSION} " +
            "pid=${Process.myPid()} uid=${Process.myUid()}"

    override fun create(width: Int, height: Int, dpi: Int, captureSurface: Surface?): Int {
        val ctx = serviceContext ?: SystemContext.get()
        if (ctx == null || captureSurface == null) {
            PrivilegedLog.e("create: missing context or surface")
            return -1
        }
        val target = display ?: PrivilegedVirtualDisplay(ctx).also { display = it }
        return target.create(width, height, dpi, captureSurface)
    }

    override fun displayId(): Int = display?.displayId() ?: -1

    override fun currentPackage(): String? = display?.currentPackage()

    override fun launch(intent: Intent?, forceStopIfElsewhere: Boolean): Boolean {
        if (intent == null) return false
        return display?.launch(intent, forceStopIfElsewhere) ?: false
    }

    override fun destroyDisplay() {
        display?.destroy()
        display = null
        PrivilegedLog.i("Virtual display destroyed (service kept alive)")
    }

    override fun destroy() {
        PrivilegedLog.i("VirtualScreenUserService destroy")
        destroyDisplay()
        exitProcess(0)
    }
}
