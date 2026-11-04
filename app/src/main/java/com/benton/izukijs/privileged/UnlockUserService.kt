package com.benton.izukijs.privileged

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.benton.izukijs.privileged.internal.GestureRecorder
import com.benton.izukijs.privileged.internal.PrivilegedLog
import com.benton.izukijs.privileged.internal.WakeUnlockController
import com.benton.izukijs.service.UnlockResult
import kotlin.system.exitProcess

/**
 * Shizuku UserService 运行的入口。
 *
 * Shizuku v13 会优先调用带 Context 的构造函数；类名由 app 侧 `UserServiceArgs` 指定，
 * 所以 release 构建必须用 keep 规则保留。
 */
@Keep
class UnlockUserService : IUnlockService.Stub {

    private var controller: WakeUnlockController? = null
    private var recorder: GestureRecorder? = null

    constructor() {
        init(null)
    }

    @Keep
    constructor(context: Context) {
        init(context)
    }

    private fun init(context: Context?) {
        val controller = WakeUnlockController(context)
        this.controller = controller
        this.recorder = GestureRecorder(context, controller)
        PrivilegedLog.i("UnlockUserService started: pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun version(): String =
        "izuki-unlock-${UnlockProtocol.SERVICE_VERSION} pid=${Process.myPid()} uid=${Process.myUid()}"

    override fun unlock(credential: String?): String =
        (controller?.unlock(credential.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    override fun unlockWithGesture(gestureJson: String?): String =
        (controller?.unlockWithGesture(gestureJson.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    override fun testUnlock(credential: String?): String =
        (controller?.testUnlock(credential.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    override fun testUnlockGesture(gestureJson: String?): String =
        (controller?.testUnlockGesture(gestureJson.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    override fun lockAndSleep(): String =
        (controller?.lockAndSleep() ?: UnlockResult.UNSUPPORTED).name

    override fun startGestureRecord(timeoutMs: Int) {
        recorder?.start(timeoutMs)
    }

    override fun pollGestureRecord(): String = recorder?.poll() ?: """{"status":"IDLE"}"""

    override fun cancelGestureRecord() {
        recorder?.cancel()
    }

    override fun isScreenOn(): Boolean = controller?.isScreenOn() ?: false

    override fun destroy() {
        PrivilegedLog.i("UnlockUserService destroy")
        runCatching { recorder?.cancel() }
        exitProcess(0)
    }
}
