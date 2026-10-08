package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.LogBus
import com.benton.izukijs.service.OverlayCoordinator
import com.benton.izukijs.service.ScreenCapture

/**
 * 设备能力 API 的组装结果。
 *
 * 脚本运行时（[com.benton.izukijs.runtime.JsEngine]）与 MCP 服务共用同一套 API 对象，
 * 保证「AI / MCP 操作设备」与脚本行为完全一致。截图统一走「持续录屏优先、控制后端兜底」
 * 的策略，并在截图前后经 [OverlayCoordinator] 临时隐藏悬浮层。
 */
class DeviceApiBundle(
    val screenshotProvider: () -> Bitmap?,
    val inputApi: InputApi,
    val deviceApi: DeviceApi,
    val appApi: AppApi,
    val shellApi: ShellApi,
    val ocrApi: OcrApi,
    val selectorApi: SelectorApi,
) {
    companion object {
        fun create(
            context: Context,
            controllers: ControllerManager,
            screenCapture: ScreenCapture,
            ocrProcessor: OcrProcessor,
            logBus: LogBus,
        ): DeviceApiBundle {
            val screenshotProvider: () -> Bitmap? = {
                OverlayCoordinator.withoutOverlay {
                    val projected = screenCapture.capture()
                    if (projected != null) {
                        logBus.debug("捕获屏幕 ${projected.width}×${projected.height} → 持续录屏")
                        projected
                    } else {
                        val controller = controllers.controllerFor(Capability.SCREENSHOT)
                        val shot = controller?.screenshot()
                        if (shot != null) {
                            logBus.debug("捕获屏幕 ${shot.width}×${shot.height} → ${controller.mode.displayName}")
                        } else {
                            logBus.warn("捕获屏幕失败：无可用截图后端")
                        }
                        shot
                    }
                }
            }
            return DeviceApiBundle(
                screenshotProvider = screenshotProvider,
                inputApi = InputApi(controllers, logBus),
                deviceApi = DeviceApi(context),
                appApi = AppApi(context, logBus),
                shellApi = ShellApi(controllers, logBus),
                ocrApi = OcrApi(ocrProcessor, screenshotProvider, logBus),
                selectorApi = SelectorApi(logBus) {
                    controllers.controllerFor(Capability.NODE_TREE) as? AccessibilityController
                },
            )
        }
    }
}
