package com.benton.izukijs.runtime

import android.content.Context
import android.graphics.Bitmap
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.model.Capability
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.api.AiApi
import com.benton.izukijs.runtime.api.AppApi
import com.benton.izukijs.runtime.api.ConsoleApi
import com.benton.izukijs.runtime.api.DeviceApi
import com.benton.izukijs.runtime.api.GlobalApi
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.PermissionsApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.ScreenCapture
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.io.Closeable

/**
 * QuickJS 运行时封装。每个脚本一个实例，拥有独立的运行时与上下文。
 */
class JsEngine(
    private val context: Context,
    private val controllers: ControllerManager,
    private val screenCapture: ScreenCapture,
    private val ocrProcessor: OcrProcessor,
    private val logBus: LogBus,
    private val aiConfigRepository: AiConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
) : Closeable {

    private var runtime: QuickJS? = null
    private var jsContext: JSContext? = null

    @Volatile
    var exitRequested: Boolean = false
        private set

    fun requestExit() {
        exitRequested = true
    }

    fun execute(source: String, fileName: String) {
        val rt = QuickJS.createRuntimeWithEventQueue()
        runtime = rt
        val ctx = rt.createContext()
        jsContext = ctx
        bindApis(ctx)
        ctx.executeVoidScript(PRELUDE, "izuki_prelude.js")
        ctx.executeVoidScript(source, fileName)
    }

    private fun bindApis(ctx: JSContext) {
        val screenshotProvider: () -> Bitmap? = {
            screenCapture.capture() ?: controllers.controllerFor(Capability.SCREENSHOT)?.screenshot()
        }
        val globalApi = GlobalApi(
            context = context,
            logBus = logBus,
            screenshotProvider = screenshotProvider,
            captureSettingsProvider = { captureSettingsRepository.current() },
            onExit = { exitRequested = true },
            isExitRequested = { exitRequested },
        )
        val inputApi = InputApi(controllers)
        val deviceApi = DeviceApi(context)
        val appApi = AppApi(context)
        val shellApi = ShellApi(controllers)
        val ocrApi = OcrApi(ocrProcessor, screenshotProvider)
        val selectorApi = SelectorApi {
            controllers.controllerFor(Capability.NODE_TREE) as? AccessibilityController
        }

        ctx.appendJavascriptInterface(globalApi)
        ctx.appendJavascriptInterface(inputApi)
        ctx.addJavascriptInterface(deviceApi, "device")
        ctx.addJavascriptInterface(appApi, "app")
        ctx.addJavascriptInterface(ConsoleApi(logBus), "console")
        ctx.addJavascriptInterface(shellApi, "shell")
        ctx.addJavascriptInterface(ImageApi(screenshotProvider, logBus), "images")
        ctx.addJavascriptInterface(ocrApi, "ocr")
        ctx.addJavascriptInterface(PermissionsApi(context, controllers, screenCapture), "permissions")
        ctx.addJavascriptInterface(selectorApi, "selector")
        ctx.addJavascriptInterface(
            AiApi(
                configRepository = aiConfigRepository,
                inputApi = inputApi,
                selectorApi = selectorApi,
                ocrApi = ocrApi,
                appApi = appApi,
                shellApi = shellApi,
                deviceApi = deviceApi,
                screenshotPathProvider = { globalApi.captureScreen("") },
                nodeTreeProvider = { controllers.controllerFor(Capability.NODE_TREE)?.nodeTree() },
                logBus = logBus,
                isExitRequested = { exitRequested },
            ),
            "ai",
        )
    }

    override fun close() {
        runCatching { jsContext?.close() }
        runCatching { runtime?.close() }
        jsContext = null
        runtime = null
    }

    private companion object {
        /** 把原生的 primitive 返回方法包装成对脚本更友好的对象。 */
        val PRELUDE = """
            (function () {
              if (typeof images !== 'undefined') {
                images.findImage = function (template, threshold) {
                  var raw = images.findImageRaw(template, (threshold == null ? 0.8 : threshold));
                  if (!raw) return null;
                  var p = raw.split(',');
                  return { x: parseInt(p[0], 10), y: parseInt(p[1], 10), confidence: parseFloat(p[2]) };
                };
                images.findColor = function (color, threshold) {
                  var raw = images.findColorRaw(color, (threshold == null ? 4 : threshold));
                  if (!raw) return null;
                  var p = raw.split(',');
                  return { x: parseInt(p[0], 10), y: parseInt(p[1], 10) };
                };
              }
              if (typeof ocr !== 'undefined') {
                ocr.find = function (text) {
                  var raw = ocr.findRaw(text);
                  if (!raw) return null;
                  var p = raw.split(',');
                  return {
                    x: parseInt(p[0], 10),
                    y: parseInt(p[1], 10),
                    width: parseInt(p[2], 10),
                    height: parseInt(p[3], 10)
                  };
                };
              }
            })();
        """.trimIndent()
    }
}
