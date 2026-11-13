package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenOptions
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenState
import org.json.JSONObject

/**
 * 脚本侧虚拟屏 API。挂在全局 `virtualScreen` 命名空间，也可通过
 * `require("virtualScreen")` 取得同一个对象。
 */
class VirtualScreenApi(
    private val manager: VirtualScreenManager,
    private val runModeProvider: () -> RunMode,
    private val captureProvider: ((ScreenTarget, Long) -> Bitmap?)? = null,
    private val screenshotStore: ScreenshotStore? = null,
    private val logBus: LogBus? = null,
) {

    @JavascriptInterface
    fun available(): Boolean = manager.available()

    @JavascriptInterface
    fun active(): Boolean = manager.isActive()

    @JavascriptInterface
    fun mode(): String =
        if (manager.isActive()) RunMode.BACKGROUND.name.lowercase()
        else runModeProvider().name.lowercase()

    /** 允许的运行模式，逗号分隔；prelude 会包装成数组。 */
    @JavascriptInterface
    fun allowedRaw(): String =
        manager.allowedModes()
            .sortedBy { it.ordinal }
            .joinToString(",") { it.name.lowercase() }

    @JavascriptInterface
    fun startRaw(optionsJson: String): Boolean {
        val options = if (optionsJson.isBlank()) {
            null
        } else {
            val json = runCatching { JSONObject(optionsJson) }.getOrNull() ?: run {
                logBus?.warn("virtualScreen.start 参数解析失败：$optionsJson")
                return false
            }
            val base = manager.configuredResolution()
            VirtualScreenOptions(
                resolution = VirtualScreenResolution(
                    width = json.optInt("width", json.optInt("w", base.width)),
                    height = json.optInt("height", json.optInt("h", base.height)),
                    dpi = json.optInt("dpi", base.dpi),
                ),
            )
        }
        return manager.startBlocking(options)
    }

    @JavascriptInterface
    fun stop(): Boolean {
        if (!manager.isActive()) return true
        manager.stopBlocking()
        return !manager.isActive()
    }

    @JavascriptInterface
    fun displayId(): Int = manager.displayId()

    @JavascriptInterface
    fun targetRaw(): String = manager.target().displayName()

    @JavascriptInterface
    fun setTargetRaw(value: String): Boolean {
        val target = ScreenTarget.from(value) ?: return false
        return manager.setTarget(target)
    }

    @JavascriptInterface
    fun awaitFrameRaw(timeoutMs: Int): Boolean =
        manager.awaitFrame(timeoutMs.toLong().coerceAtLeast(0L))

    @JavascriptInterface
    fun frameSeqRaw(): Double = manager.frameSeq().toDouble()

    /** 等待比 [afterFrameSeq] 更新的帧；[afterFrameSeq] < 0 时以当前帧为基准。 */
    @JavascriptInterface
    fun awaitChangeRaw(afterFrameSeq: Double, timeoutMs: Int): Boolean {
        val baseline = if (afterFrameSeq >= 0.0) afterFrameSeq.toLong() else manager.frameSeq()
        return manager.awaitFrameAfter(baseline, timeoutMs.toLong().coerceAtLeast(0L))
    }

    @JavascriptInterface
    fun awaitStableRaw(optionsJson: String): String {
        val options = runCatching {
            if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
        }.getOrDefault(JSONObject())
        val stableMs = options.optLong("stableMs", options.optLong("stable_ms", DEFAULT_STABLE_MS))
        val timeoutMs = options.optLong("timeoutMs", options.optLong("timeout_ms", DEFAULT_STABLE_TIMEOUT_MS))
        val intervalMs = options.optLong("intervalMs", options.optLong("interval_ms", DEFAULT_STABLE_INTERVAL_MS))
        val result = manager.awaitStable(stableMs, timeoutMs, intervalMs)
        return JSONObject().apply {
            put("ok", result.ok)
            put("frameSeq", result.frameSeq)
            put("elapsedMs", result.elapsedMs)
        }.toString()
    }

    /** 直接捕获当前目标屏并保存；[source] 缺省为 virtual。 */
    @JavascriptInterface
    fun captureRaw(path: String, source: String): String? {
        val target = ScreenTarget.from(source) ?: ScreenTarget.VIRTUAL
        val bitmap = captureProvider?.invoke(target, CAPTURE_WAIT_MS) ?: return null
        val store = screenshotStore ?: run {
            bitmap.recycle()
            return null
        }
        return store.save(bitmap, path)
    }

    @JavascriptInterface
    fun sizeRaw(): String {
        val resolution = manager.size() ?: manager.configuredResolution()
        return JSONObject().apply {
            put("width", resolution.width)
            put("height", resolution.height)
            put("dpi", resolution.dpi)
        }.toString()
    }

    @JavascriptInterface
    fun launch(packageName: String): Boolean = manager.launchBlocking(packageName)

    @JavascriptInterface
    fun infoRaw(): String {
        val state = manager.state.value
        val resolution = manager.size() ?: manager.configuredResolution()
        return JSONObject().apply {
            put("available", manager.available())
            put("active", manager.isActive())
            put("mode", mode())
            put("displayId", manager.displayId())
            put("target", manager.target().displayName())
            put("resolvedTarget", manager.resolvedTarget().displayName())
            put("frameSeq", manager.frameSeq())
            put("canCapture", manager.canCapture())
            put("width", resolution.width)
            put("height", resolution.height)
            put("dpi", resolution.dpi)
            manager.lastStopReason()?.let { put("lastStopReason", it) }
            if (manager.lastStopAt() > 0L) put("lastStopAt", manager.lastStopAt())
            put("state", when (state) {
                VirtualScreenState.Idle -> "idle"
                VirtualScreenState.Starting -> "starting"
                is VirtualScreenState.Active -> "active"
                is VirtualScreenState.Failed -> "failed"
            })
            if (state is VirtualScreenState.Failed) put("message", state.message)
        }.toString()
    }

    private companion object {
        val CAPTURE_WAIT_MS: Long get() = DeveloperTuning.current.captureWaitMs.toLong()

        const val DEFAULT_STABLE_MS = 300L
        const val DEFAULT_STABLE_TIMEOUT_MS = 3_000L
        const val DEFAULT_STABLE_INTERVAL_MS = 80L
    }
}
