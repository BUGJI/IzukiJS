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

    @Volatile
    private var serviceError: String? = null

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

    override fun create(
        width: Int,
        height: Int,
        dpi: Int,
        captureSurface: Surface?,
        showSystemDecorations: Boolean,
    ): Int {
        serviceError = null
        val ctx = serviceContext ?: SystemContext.get()
        if (ctx == null || captureSurface == null) {
            serviceError = "缺少 Context 或采集 Surface"
            PrivilegedLog.e("create: missing context or surface")
            return -1
        }
        val target = display ?: PrivilegedVirtualDisplay(ctx).also { display = it }
        return runCatching {
            target.create(width, height, dpi, captureSurface, showSystemDecorations)
        }
            .getOrElse { throwable ->
                serviceError = throwable.message ?: throwable.javaClass.name
                PrivilegedLog.e("create failed", throwable)
                -1
            }
            .also { displayId ->
                if (displayId < 0) {
                    serviceError = target.lastError ?: serviceError ?: "createVirtualDisplay failed"
                }
            }
    }

    override fun lastError(): String? = serviceError ?: display?.lastError

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
