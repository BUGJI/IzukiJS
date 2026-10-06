package com.benton.izukijs.controller.shizuku

import android.content.Context
import android.content.pm.PackageManager
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus
import rikka.shizuku.Shizuku

/**
 * Shizuku 生命周期与授权管理。授权成功后向 [ControllerManager] 注册 Shizuku 控制后端。
 */
class ShizukuManager(
    private val context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
) {

    private val shell = ShizukuShell()
    private val controller = ShizukuController(context, shell)

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            logBus.success("Shizuku 授权成功")
            register()
        } else {
            logBus.warn("Shizuku 授权被拒绝")
            controllers.unregister(ControlMode.SHIZUKU)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        logBus.info("Shizuku 已连接")
        maybeRegister()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        logBus.warn("Shizuku 连接已断开")
        controllers.unregister(ControlMode.SHIZUKU)
    }

    fun init() {
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { logBus.warn("Shizuku 初始化失败: ${it.message}") }
        maybeRegister()
    }

    fun isShizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching {
            Shizuku.requestPermission(requestCode)
        }.onFailure { logBus.error("Shizuku 授权请求失败: ${it.message}") }
    }

    /** 撤回 Shizuku 后端，并尝试打开 Shizuku 应用以便用户彻底撤销。 */
    fun revoke() {
        controllers.unregister(ControlMode.SHIZUKU)
        logBus.warn("已断开 Shizuku 后端。如需彻底撤销授权，请在 Shizuku 应用中操作。")
        runCatching {
            val intent = context.packageManager
                .getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
        }
    }

    fun refresh() = maybeRegister()

    private fun maybeRegister() {
        if (shell.isAvailable()) {
            register()
        } else {
            controllers.unregister(ControlMode.SHIZUKU)
        }
    }

    private fun register() {
        controllers.register(controller)
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
