package com.benton.izukijs.runtime.api

import android.content.Context
import android.graphics.Bitmap
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.service.OverlayCoordinator
import com.benton.izukijs.service.ScreenCapture
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager

/**
 * 设备能力 API 的组装结果。
 *
 * 脚本运行时（[com.benton.izukijs.runtime.JsEngine]）与 MCP 服务共用同一套 API 对象，
 * 保证「AI / MCP 操作设备」与脚本行为完全一致。截图统一走「持续录屏优先、控制后端兜底」
 * 的策略，并在截图前后经 [OverlayCoordinator] 临时隐藏悬浮层。
 */
class DeviceApiBundle(
    val screenshotProvider: () -> Bitmap?,
    val captureProvider: (ScreenTarget, Long) -> Bitmap?,
    val inputApi: InputApi,
    val deviceApi: DeviceApi,
    val appApi: AppApi,
    val shellApi: ShellApi,
    val ocrApi: OcrApi,
    val selectorApi: SelectorApi,
    val notificationApi: NotificationApi,
) {
    companion object {
        private const val DEFAULT_CAPTURE_WAIT_MS = 1_000L

        fun create(
            context: Context,
            controllers: ControllerManager,
            screenCapture: ScreenCapture,
            ocrProcessor: OcrProcessor,
            logBus: LogBus,
            appListEnabled: () -> Boolean = { true },
            progressNotificationEnabled: () -> Boolean = { true },
            virtualScreen: VirtualScreenManager? = null,
            isExitRequested: () -> Boolean = { false },
        ): DeviceApiBundle {
            val mainScreenshotProvider: () -> Bitmap? = {
                OverlayCoordinator.withoutOverlay {
                    val projected = screenCapture.capture()
                    if (projected != null) {
                        logBus.debug("捕获屏幕 ${projected.width}×${projected.height} → 持续录屏")
                        projected
                    } else {
                        // 主屏捕获不受虚拟屏 display 定向限制，必要时绕过 controllerFor 的过滤。
                        val controller = controllers.controllerFor(Capability.SCREENSHOT)
                            ?: controllers.all().firstOrNull {
                                !controllers.isDisabled(it.mode) &&
                                    it.isReady() &&
                                    Capability.SCREENSHOT in it.capabilities()
                            }
                        val shot = controller?.screenshotForDisplay(null)
                        if (shot != null) {
                            logBus.debug("捕获屏幕 ${shot.width}×${shot.height} → ${controller.mode.displayName}")
                        } else {
                            logBus.warn("捕获屏幕失败：无可用截图后端")
                        }
                        shot
                    }
                }
            }
            val captureProvider: (ScreenTarget, Long) -> Bitmap? = { source, waitMs ->
                val resolved = when (source) {
                    ScreenTarget.AUTO -> virtualScreen?.resolvedTarget() ?: ScreenTarget.MAIN
                    ScreenTarget.VIRTUAL -> ScreenTarget.VIRTUAL
                    ScreenTarget.MAIN -> ScreenTarget.MAIN
                }
                when (resolved) {
                    ScreenTarget.VIRTUAL -> {
                        val activeVirtual = virtualScreen?.takeIf { it.isActive() }
                        if (activeVirtual == null) {
                            logBus.warn("请求捕获虚拟屏，但虚拟屏未运行")
                            null
                        } else {
                            val virtual = activeVirtual.capture(waitMs)
                            if (virtual != null) {
                                logBus.debug("捕获虚拟屏 ${virtual.width}×${virtual.height}")
                            } else {
                                logBus.warn(
                                    "虚拟屏暂无可用的画面帧；虚拟屏可能刚启动或尚未启动应用",
                                )
                            }
                            virtual
                        }
                    }

                    ScreenTarget.MAIN, ScreenTarget.AUTO -> mainScreenshotProvider()
                }
            }
            val screenshotProvider: () -> Bitmap? = {
                captureProvider(ScreenTarget.AUTO, DEFAULT_CAPTURE_WAIT_MS)
            }
            val shellExec: (String) -> String? = { cmd ->
                controllers.controllerFor(Capability.SHELL)?.shell(cmd)?.stdout
            }
            return DeviceApiBundle(
                screenshotProvider = screenshotProvider,
                captureProvider = captureProvider,
                inputApi = InputApi(controllers, logBus),
                deviceApi = DeviceApi(context, virtualScreen),
                appApi = AppApi(context, logBus, shellExec, appListEnabled, virtualScreen),
                shellApi = ShellApi(controllers, logBus),
                ocrApi = OcrApi(ocrProcessor, screenshotProvider, logBus),
                selectorApi = SelectorApi(
                    logBus = logBus,
                    controllerProvider = {
                        controllers.controllerFor(Capability.NODE_TREE) as? AccessibilityController
                    },
                    isExitRequested = isExitRequested,
                ),
                notificationApi = NotificationApi(
                    context = context,
                    logBus = logBus,
                    progressSettingEnabled = progressNotificationEnabled,
                ),
            )
        }
    }
}
