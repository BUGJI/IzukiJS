package com.benton.izukijs.controller.root

import android.content.Context
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.runtime.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Root 后端管理。检测与注册放到后台线程，避免阻塞启动。
 */
class RootManager(
    context: Context,
    private val controllers: ControllerManager,
    private val logBus: LogBus,
    private val scope: CoroutineScope,
) {

    private val shell = RootShell(context)
    private val controller = RootController(context, shell)

    fun init() {
        scope.launch(Dispatchers.IO) {
            val ok = shell.refresh()
            if (ok) {
                logBus.success("Root 已就绪")
                controllers.register(controller)
            } else {
                controllers.unregister(ControlMode.ROOT)
            }
        }
    }

    fun refresh() = init()

    fun isAvailable(): Boolean = shell.isAvailable()

    /** 撤回 Root 后端。 */
    fun revoke() {
        shell.markUnavailable()
        controllers.unregister(ControlMode.ROOT)
        logBus.warn("已撤回 Root 后端。如需彻底撤销，请在 Magisk/Root 管理器中操作。")
    }
}
