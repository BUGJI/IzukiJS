package com.benton.izukijs.privileged

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.benton.izukijs.privileged.internal.GestureRecorder
import com.benton.izukijs.privileged.internal.InteractionRecorder
import com.benton.izukijs.privileged.internal.PrivilegedLog
import com.benton.izukijs.privileged.internal.WakeUnlockController
import com.benton.izukijs.model.UnlockResult
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
    private var interactionRecorder: InteractionRecorder? = null

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
        this.interactionRecorder = InteractionRecorder(context)
        PrivilegedLog.i("UnlockUserService started: pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun version(): String =
        "izuki-unlock-${UnlockProtocol.SERVICE_VERSION} pid=${Process.myPid()} uid=${Process.myUid()}"

    @Synchronized
    override fun unlock(credential: String?): String =
        (controller?.unlock(credential.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    @Synchronized
    override fun unlockWithGesture(gestureJson: String?): String =
        (controller?.unlockWithGesture(gestureJson.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    @Synchronized
    override fun testUnlock(credential: String?): String =
        (controller?.testUnlock(credential.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    @Synchronized
    override fun testUnlockGesture(gestureJson: String?): String =
        (controller?.testUnlockGesture(gestureJson.orEmpty()) ?: UnlockResult.UNSUPPORTED).name

    @Synchronized
    override fun lockAndSleep(): String =
        (controller?.lockAndSleep() ?: UnlockResult.UNSUPPORTED).name

    @Synchronized
    override fun startGestureRecord(timeoutMs: Int) {
        recorder?.start(timeoutMs)
    }

    @Synchronized
    override fun pollGestureRecord(): String = recorder?.poll() ?: """{"status":"IDLE"}"""

    @Synchronized
    override fun cancelGestureRecord() {
        recorder?.cancel()
    }

    @Synchronized
    override fun isScreenOn(): Boolean = controller?.isScreenOn() ?: false

    @Synchronized
    override fun startInteractionRecord(timeoutMs: Int) {
        interactionRecorder?.start(timeoutMs)
    }

    @Synchronized
    override fun pollInteractionRecord(): String =
        interactionRecorder?.poll() ?: """{"status":"IDLE","events":[]}"""

    @Synchronized
    override fun stopInteractionRecord() {
        interactionRecorder?.stop()
    }

    override fun destroy() {
        PrivilegedLog.i("UnlockUserService destroy")
        runCatching { recorder?.cancel() }
        runCatching { interactionRecorder?.stop() }
        exitProcess(0)
    }
}
