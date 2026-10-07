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
import com.benton.izukijs.runtime.api.EnvApi
import com.benton.izukijs.runtime.api.GlobalApi
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.runtime.api.InputApi
import com.benton.izukijs.runtime.api.ModuleApi
import com.benton.izukijs.runtime.api.OcrApi
import com.benton.izukijs.runtime.api.PermissionsApi
import com.benton.izukijs.runtime.api.SelectorApi
import com.benton.izukijs.runtime.api.ShellApi
import com.benton.izukijs.runtime.api.StateApi
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.service.OverlayCoordinator
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
    private val scriptEnv: ScriptEnv,
    private val moduleSourceProvider: (String) -> String?,
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
            OverlayCoordinator.withoutOverlay {
                screenCapture.capture() ?: controllers.controllerFor(Capability.SCREENSHOT)?.screenshot()
            }
        }
        val globalApi = GlobalApi(
            context = context,
            logBus = logBus,
            screenshotProvider = screenshotProvider,
            captureSettingsProvider = { captureSettingsRepository.current() },
            onExit = { exitRequested = true },
            isExitRequested = { exitRequested },
        )
        val inputApi = InputApi(controllers, logBus)
        val deviceApi = DeviceApi(context)
        val appApi = AppApi(context, logBus)
        val shellApi = ShellApi(controllers, logBus)
        val ocrApi = OcrApi(ocrProcessor, screenshotProvider)
        val selectorApi = SelectorApi(logBus) {
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
        ctx.addJavascriptInterface(EnvApi(scriptEnv), "env")
        ctx.addJavascriptInterface(StateApi(scriptEnv), "state")
        ctx.addJavascriptInterface(ModuleApi(moduleSourceProvider), "modules")
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
            (function (global) {
              var __modules = {};
              var __loading = {};
              global.require = function (name) {
                var key = String(name);
                if (Object.prototype.hasOwnProperty.call(__modules, key)) return __modules[key].exports;
                if (__loading[key]) throw new Error("检测到循环依赖: " + key);
                var src = (typeof modules !== 'undefined' && modules.source) ? modules.source(key) : null;
                if (src == null) throw new Error("找不到脚本模块: " + key);
                __loading[key] = true;
                var mod = { exports: {} };
                __modules[key] = mod;
                try {
                  var factory;
                  try {
                    factory = new Function("module", "exports", "require", src);
                  } catch (e) {
                    factory = function (module, exports, require) { eval(src); };
                  }
                  factory(mod, mod.exports, global.require);
                } finally {
                  delete __loading[key];
                }
                return mod.exports;
              };
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
              if (typeof gestureRaw === 'function') {
                global.gesture = function (strokes) {
                  if (strokes == null) return false;
                  try { return gestureRaw(JSON.stringify(strokes)); }
                  catch (e) { return false; }
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
              if (typeof env !== 'undefined' && env.allRaw) {
                env.get = function (key, fallback) {
                  if (fallback === undefined || fallback === null) return env.getRaw(key);
                  return env.getOrRaw(key, String(fallback));
                };
                env.all = function () { return JSON.parse(env.allRaw()); };
              }
              if (typeof state !== 'undefined' && state.allRaw) {
                state.get = function (key, fallback) {
                  if (fallback === undefined || fallback === null) return state.getRaw(key);
                  return state.getOrRaw(key, String(fallback));
                };
                state.all = function () { return JSON.parse(state.allRaw()); };
              }
            })(typeof globalThis !== 'undefined' ? globalThis : this);
        """.trimIndent()
    }
}
