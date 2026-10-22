package com.benton.izukijs.runtime

import android.content.Context
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerManager
import com.benton.izukijs.controller.UiautomatorDumper
import com.benton.izukijs.ocr.OcrProcessor
import com.benton.izukijs.runtime.api.AiApi
import com.benton.izukijs.runtime.api.ClipboardApi
import com.benton.izukijs.runtime.api.ConsoleApi
import com.benton.izukijs.runtime.api.DeviceApiBundle
import com.benton.izukijs.runtime.api.EnvApi
import com.benton.izukijs.runtime.api.FileApi
import com.benton.izukijs.runtime.api.GlobalApi
import com.benton.izukijs.runtime.api.HttpApi
import com.benton.izukijs.runtime.api.ImageApi
import com.benton.izukijs.runtime.api.ModuleApi
import com.benton.izukijs.runtime.api.PermissionsApi
import com.benton.izukijs.runtime.api.StateApi
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
    private val scriptEnv: ScriptEnv,
    private val runtimeSettings: RuntimeSettingsRepository,
    private val httpSettingsRepository: HttpSettingsRepository,
    private val moduleSourceProvider: (String) -> String?,
) : Closeable {

    private var runtime: QuickJS? = null
    private var jsContext: JSContext? = null

    @Volatile
    private var interrupt: QuickJsInterrupt? = null

    @Volatile
    private var watchdog: Thread? = null

    @Volatile
    var exitRequested: Boolean = false
        private set

    /** 是否因运行超时被中断（与用户主动停止区分）。 */
    @Volatile
    var timedOut: Boolean = false
        private set

    fun requestExit() {
        exitRequested = true
        interrupt?.stop()
    }

    /**
     * @param timeoutMs 运行超时（毫秒），<= 0 表示不限制。超时由看门狗请求退出，
     *   并通过 [QuickJsInterrupt] 打断纯计算死循环。
     */
    fun execute(
        source: String,
        fileName: String,
        timeoutMs: Long = 0L,
        needs: Set<String>? = null,
    ) {
        val rt = QuickJS.createRuntimeWithEventQueue()
        runtime = rt
        val installed = QuickJsInterrupt.install(rt)
        interrupt = installed
        if (exitRequested) installed?.stop()
        val ctx = rt.createContext()
        jsContext = ctx
        bindApis(ctx, needs)
        startWatchdog(timeoutMs)
        try {
            ctx.executeVoidScript(PRELUDE, "izuki_prelude.js")
            ctx.executeVoidScript(source, fileName)
        } finally {
            stopWatchdog()
        }
    }

    private fun startWatchdog(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        val thread = Thread({
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) {
                        if (!exitRequested) {
                            timedOut = true
                            requestExit()
                        }
                        return@Thread
                    }
                    Thread.sleep(minOf(WATCHDOG_INTERVAL_MS, remaining / 1_000_000L + 1L))
                }
            } catch (_: InterruptedException) {
                // 脚本已结束或停止，正常退出。
            }
        }, "izuki-script-timeout").apply { isDaemon = true }
        watchdog = thread
        thread.start()
    }

    private fun stopWatchdog() {
        watchdog?.interrupt()
        watchdog = null
    }

    private fun bindApis(ctx: JSContext, needs: Set<String>?) {
        val bundle = DeviceApiBundle.create(
            context = context,
            controllers = controllers,
            screenCapture = screenCapture,
            ocrProcessor = ocrProcessor,
            logBus = logBus,
            appListEnabled = { runtimeSettings.current().appList },
            isExitRequested = { exitRequested },
        )
        val globalApi = GlobalApi(
            context = context,
            logBus = logBus,
            screenshotProvider = bundle.screenshotProvider,
            captureSettingsProvider = { captureSettingsRepository.current() },
            onExit = { requestExit() },
            isExitRequested = { exitRequested },
        )
        val inputApi = bundle.inputApi
        val deviceApi = bundle.deviceApi
        val appApi = bundle.appApi
        val shellApi = bundle.shellApi
        val ocrApi = bundle.ocrApi
        val selectorApi = bundle.selectorApi
        val allowed: (String) -> Boolean = { name -> needs == null || name in needs }

        ctx.appendJavascriptInterface(globalApi)
        ctx.appendJavascriptInterface(inputApi)
        ctx.addJavascriptInterface(deviceApi, "device")
        ctx.addJavascriptInterface(ConsoleApi(logBus), "console")
        ctx.addJavascriptInterface(
            PermissionsApi(
                context,
                controllers,
                screenCapture,
                { runtimeSettings.current().appList },
                { httpSettingsRepository.current().enabled },
            ),
            "permissions",
        )
        ctx.addJavascriptInterface(EnvApi(scriptEnv), "env")
        ctx.addJavascriptInterface(StateApi(scriptEnv), "state")
        ctx.addJavascriptInterface(ModuleApi(moduleSourceProvider), "modules")

        if (allowed("app")) ctx.addJavascriptInterface(appApi, "app")
        if (allowed("shell")) ctx.addJavascriptInterface(shellApi, "shell")
        if (allowed("images")) {
            ctx.addJavascriptInterface(ImageApi(context, bundle.screenshotProvider, logBus), "images")
        }
        if (allowed("ocr")) ctx.addJavascriptInterface(ocrApi, "ocr")
        if (allowed("notify")) ctx.addJavascriptInterface(bundle.notificationApi, "notify")
        if (allowed("fs")) ctx.addJavascriptInterface(FileApi(logBus), "fs")
        if (allowed("selector")) ctx.addJavascriptInterface(selectorApi, "selector")
        if (allowed("clipboard")) {
            ctx.addJavascriptInterface(ClipboardApi(context, logBus), "clipboard")
        }
        if (allowed("http")) {
            ctx.addJavascriptInterface(
                HttpApi(logBus) { httpSettingsRepository.current() },
                "http",
            )
        }
        if (allowed("ai")) {
            ctx.addJavascriptInterface(
                AiApi(
                    configRepository = aiConfigRepository,
                    inputApi = inputApi,
                    selectorApi = selectorApi,
                    ocrApi = ocrApi,
                    appApi = appApi,
                    shellApi = shellApi,
                    deviceApi = deviceApi,
                    notificationApi = bundle.notificationApi,
                    screenshotPathProvider = { globalApi.captureScreen("") },
                    nodeTreeProvider = { UiautomatorDumper.read(controllers, logBus) },
                    logBus = logBus,
                    isExitRequested = { exitRequested },
                ),
                "ai",
            )
        }
    }

    override fun close() {
        stopWatchdog()
        runCatching { interrupt?.close() }
        interrupt = null
        runCatching { jsContext?.close() }
        runCatching { runtime?.close() }
        jsContext = null
        runtime = null
    }

    private companion object {
        const val WATCHDOG_INTERVAL_MS = 200L

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
                var __regionJson = function (region) {
                  return region == null ? "" : JSON.stringify(region);
                };
                images.findImage = function (template, threshold, region) {
                  var raw = images.findImageRegionRaw(
                    template,
                    (threshold == null ? 0.8 : threshold),
                    __regionJson(region)
                  );
                  if (!raw) return null;
                  var p = raw.split(',');
                  return { x: parseInt(p[0], 10), y: parseInt(p[1], 10), confidence: parseFloat(p[2]) };
                };
                images.findAllImages = function (template, threshold, region, limit) {
                  var raw = images.findAllImagesRaw(
                    template,
                    (threshold == null ? 0.8 : threshold),
                    __regionJson(region),
                    (limit == null ? 20 : limit)
                  );
                  try { return JSON.parse(raw); } catch (e) { return []; }
                };
                images.findColor = function (color, threshold, region) {
                  var raw = images.findColorRegionRaw(
                    color,
                    (threshold == null ? 4 : threshold),
                    __regionJson(region)
                  );
                  if (!raw) return null;
                  var p = raw.split(',');
                  return { x: parseInt(p[0], 10), y: parseInt(p[1], 10) };
                };
                images.findAllColors = function (color, threshold, region, limit) {
                  var raw = images.findAllColorsRaw(
                    color,
                    (threshold == null ? 4 : threshold),
                    __regionJson(region),
                    (limit == null ? 100 : limit)
                  );
                  try { return JSON.parse(raw); } catch (e) { return []; }
                };
              }
              if (typeof http !== 'undefined' && http.requestRaw) {
                var __httpOptions = function (options) {
                  var copy = {};
                  if (options) {
                    for (var key in options) {
                      if (Object.prototype.hasOwnProperty.call(options, key)) copy[key] = options[key];
                    }
                  }
                  return copy;
                };
                http.request = function (options) {
                  var raw = http.requestRaw(JSON.stringify(options || {}));
                  if (!raw) return null;
                  try { return JSON.parse(raw); } catch (e) { return null; }
                };
                http.get = function (url, options) {
                  var copy = __httpOptions(options);
                  copy.url = url;
                  copy.method = 'GET';
                  return http.request(copy);
                };
                http.post = function (url, body, options) {
                  var copy = __httpOptions(options);
                  copy.url = url;
                  copy.method = 'POST';
                  copy.body = body;
                  return http.request(copy);
                };
                http.postJson = function (url, data, options) {
                  var copy = __httpOptions(options);
                  copy.url = url;
                  copy.method = 'POST';
                  copy.headers = __httpOptions(copy.headers);
                  if (!copy.headers['Content-Type'] && !copy.headers['content-type']) {
                    copy.headers['Content-Type'] = 'application/json';
                  }
                  copy.body = JSON.stringify(data);
                  return http.request(copy);
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
              if (typeof selector !== 'undefined' && selector.findRaw) {
                var __selCriteria = function (criteria) {
                  if (criteria == null) return {};
                  if (typeof criteria === 'string') return { text: criteria };
                  return criteria;
                };
                var __selJson = function (criteria) {
                  return JSON.stringify(__selCriteria(criteria));
                };
                var __selParse = function (raw) {
                  if (!raw) return null;
                  try { return JSON.parse(raw); } catch (e) { return null; }
                };
                selector.find = function (criteria, index) {
                  return __selParse(selector.findRaw(__selJson(criteria), index == null ? 0 : index));
                };
                selector.findAll = function (criteria) {
                  try { return JSON.parse(selector.findAllRaw(__selJson(criteria))); } catch (e) { return []; }
                };
                selector.nth = function (criteria, index) {
                  return selector.find(criteria, index);
                };
                selector.exists = function (criteria) {
                  return selector.find(criteria) != null;
                };
                selector.count = function (criteria) {
                  return selector.countRaw(__selJson(criteria));
                };
                selector.click = function (criteria, index) {
                  return selector.clickRaw(__selJson(criteria), index == null ? 0 : index);
                };
                selector.longClick = function (criteria, index) {
                  return selector.longClickRaw(__selJson(criteria), index == null ? 0 : index);
                };
                selector.scrollIntoView = function (criteria, index) {
                  return selector.scrollIntoViewRaw(__selJson(criteria), index == null ? 0 : index);
                };
                selector.waitFor = function (criteria, timeoutMs, intervalMs, index) {
                  var result = __selParse(selector.waitForRaw(
                    __selJson(criteria),
                    timeoutMs == null ? 5000 : timeoutMs,
                    intervalMs == null ? 200 : intervalMs,
                    index == null ? 0 : index
                  ));
                  if (typeof shouldExit === 'function' && shouldExit()) {
                    throw new Error('IZUKI_SCRIPT_EXIT');
                  }
                  return result;
                };
                selector.waitGone = function (criteria, timeoutMs, intervalMs) {
                  var result = selector.waitGoneRaw(
                    __selJson(criteria),
                    timeoutMs == null ? 5000 : timeoutMs,
                    intervalMs == null ? 200 : intervalMs
                  );
                  if (typeof shouldExit === 'function' && shouldExit()) {
                    throw new Error('IZUKI_SCRIPT_EXIT');
                  }
                  return result;
                };
                selector.findByText = function (text, index) {
                  return selector.find({ text: text }, index);
                };
                selector.findAllByText = function (text) {
                  return selector.findAll({ text: text });
                };
                selector.findByTextContains = function (text, index) {
                  return selector.find({ textContains: text }, index);
                };
                selector.findByTextRegex = function (pattern, index) {
                  return selector.find({ textRegex: pattern }, index);
                };
                selector.findByDesc = function (desc, index) {
                  return selector.find({ desc: desc }, index);
                };
                selector.findByDescContains = function (desc, index) {
                  return selector.find({ descContains: desc }, index);
                };
                selector.findByDescRegex = function (pattern, index) {
                  return selector.find({ descRegex: pattern }, index);
                };
                selector.waitForText = function (text, timeoutMs, intervalMs) {
                  return selector.waitFor({ text: text }, timeoutMs, intervalMs);
                };
                selector.waitForId = function (id, timeoutMs, intervalMs) {
                  return selector.waitFor({ id: id }, timeoutMs, intervalMs);
                };
                selector.waitForTextContains = function (text, timeoutMs, intervalMs) {
                  return selector.waitFor({ textContains: text }, timeoutMs, intervalMs);
                };
                selector.waitForTextRegex = function (pattern, timeoutMs, intervalMs) {
                  return selector.waitFor({ textRegex: pattern }, timeoutMs, intervalMs);
                };
              }
              global.waitUntil = function (condition, timeoutMs, intervalMs) {
                if (typeof condition !== 'function') {
                  throw new Error('waitUntil 需要一个函数参数');
                }
                var timeout = (timeoutMs == null ? 10000 : Number(timeoutMs));
                var interval = (intervalMs == null ? 200 : Number(intervalMs));
                if (!isFinite(timeout) || timeout < 0) timeout = 10000;
                if (!isFinite(interval) || interval < 16) interval = 200;
                var started = Date.now();
                for (;;) {
                  if (typeof shouldExit === 'function' && shouldExit()) {
                    throw new Error('IZUKI_SCRIPT_EXIT');
                  }
                  var result = condition();
                  if (result) return result;
                  var elapsed = Date.now() - started;
                  if (elapsed >= timeout) return null;
                  sleep(Math.min(interval, timeout - elapsed));
                }
              };
              global.waitUntilGone = function (condition, timeoutMs, intervalMs) {
                if (typeof condition !== 'function') {
                  throw new Error('waitUntilGone 需要一个函数参数');
                }
                return waitUntil(function () { return !condition(); }, timeoutMs, intervalMs) != null;
              };
              if (typeof app !== 'undefined' && app.listRaw) {
                app.list = function () {
                  try { return JSON.parse(app.listRaw()); } catch (e) { return []; }
                };
              }
              if (typeof fs !== 'undefined') {
                var __fsList = fs.list;
                fs.list = function (path) {
                  var raw = __fsList(path);
                  if (!raw) return null;
                  try { return JSON.parse(raw); } catch (e) { return null; }
                };
              }
              if (typeof notify !== 'undefined') {
                var __notifyList = notify.list;
                notify.list = function () {
                  try { return JSON.parse(__notifyList()); } catch (e) { return []; }
                };
                var __notifyLatest = notify.latest;
                notify.latest = function (pkg, timeoutMs) {
                  var raw = __notifyLatest(pkg, (timeoutMs == null ? 10000 : timeoutMs));
                  if (!raw) return null;
                  try { return JSON.parse(raw); } catch (e) { return null; }
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
              // 停止脚本：Java 侧只设标志并尽快返回，绝不抛出 Java 异常（否则 QuickJS JNI 会 abort）。
              // 这里在原生调用返回后检测标志，抛出真正的 JS 异常来中断脚本执行。
              if (typeof shouldExit === 'function') {
                if (typeof sleep === 'function') {
                  var __izukiSleep = sleep;
                  global.sleep = function (ms) {
                    __izukiSleep(ms);
                    if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
                  };
                }
                if (typeof exit === 'function') {
                  var __izukiExit = exit;
                  global.exit = function () {
                    __izukiExit();
                    throw new Error("IZUKI_SCRIPT_EXIT");
                  };
                }
                if (typeof ai !== 'undefined') {
                  if (typeof ai.chat === 'function') {
                    var __izukiAiChat = ai.chat;
                    ai.chat = function (prompt, options) {
                      var r = __izukiAiChat(prompt, options);
                      if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
                      return r;
                    };
                  }
                  if (typeof ai.run === 'function') {
                    var __izukiAiRun = ai.run;
                    ai.run = function (prompt, options) {
                      var r = __izukiAiRun(prompt, options);
                      if (shouldExit()) throw new Error("IZUKI_SCRIPT_EXIT");
                      return r;
                    };
                  }
                }
              }
            })(typeof globalThis !== 'undefined' ? globalThis : this);
        """.trimIndent()
    }
}
