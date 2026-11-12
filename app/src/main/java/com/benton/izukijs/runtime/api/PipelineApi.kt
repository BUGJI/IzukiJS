package com.benton.izukijs.runtime.api

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.record.RecordingReplay
import com.benton.izukijs.controller.record.RecordingStore
import com.benton.izukijs.controller.record.ReplayOptions
import com.benton.izukijs.controller.record.ScreenshotEvent
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.developer.DeveloperSettings
import com.benton.izukijs.developer.DeveloperTuning
import com.benton.izukijs.pipeline.DevicePipelineActions
import com.benton.izukijs.pipeline.ReplayClock
import com.benton.izukijs.pipeline.ReplayPipelineActions
import com.benton.izukijs.pipeline.PipelineDumper
import com.benton.izukijs.pipeline.PipelineIssue
import com.benton.izukijs.pipeline.PipelineIssueSeverity
import com.benton.izukijs.pipeline.PipelineParser
import com.benton.izukijs.pipeline.PipelineRunOptions
import com.benton.izukijs.pipeline.PipelineRunner
import com.benton.izukijs.pipeline.PipelineSpec
import com.benton.izukijs.pipeline.PipelineValidator
import com.benton.izukijs.pipeline.ScreenshotPipelineVision
import com.benton.izukijs.vision.Box
import com.quickjs.JSArray
import com.quickjs.JSFunction
import com.quickjs.JSObject
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ConcurrentHashMap

class PipelineApi(
    private val screenshotProvider: () -> Bitmap?,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val inputApi: InputApi,
    private val appApi: AppApi,
    private val shellApi: ShellApi,
    private val visionEngine: com.benton.izukijs.vision.VisionEngine,
    private val logBus: LogBus,
    private val isExitRequested: () -> Boolean,
    private val recordingStore: RecordingStore? = null,
) {

    private val recognitions = ConcurrentHashMap<String, JSFunction>()
    private val customActions = ConcurrentHashMap<String, JSFunction>()

    @JavascriptInterface
    fun registerRecognitionRaw(name: String, fn: JSObject?): Boolean {
        val function = fn as? JSFunction ?: return false
        if (name.isBlank()) return false
        recognitions[name] = function
        logBus.debug("Pipeline 注册自定义识别: $name")
        return true
    }

    @JavascriptInterface
    fun registerActionRaw(name: String, fn: JSObject?): Boolean {
        val function = fn as? JSFunction ?: return false
        if (name.isBlank()) return false
        customActions[name] = function
        logBus.debug("Pipeline 注册自定义动作: $name")
        return true
    }

    @JavascriptInterface
    fun validateRaw(specJson: String): String = try {
        val spec = PipelineParser.parseDocument(JSONObject(specJson))
        val issues = PipelineValidator.validate(spec)
        JSONObject()
            .put("ok", issues.none { it.severity == PipelineIssueSeverity.Error })
            .put("issues", issuesToJson(issues))
            .toString()
    } catch (t: Throwable) {
        JSONObject()
            .put("ok", false)
            .put(
                "issues",
                issuesToJson(
                    listOf(PipelineIssue(PipelineIssueSeverity.Error, null, t.message ?: "Pipeline 解析失败")),
                ),
            )
            .toString()
    }

    @JavascriptInterface
    fun dumpRaw(specJson: String): String = try {
        val spec = PipelineParser.parseDocument(JSONObject(specJson))
        JSONObject().put("ok", true).put("spec", PipelineDumper.dump(spec)).toString()
    } catch (t: Throwable) {
        JSONObject().put("ok", false).put("error", t.message ?: "Pipeline 解析失败").toString()
    }

    @JavascriptInterface
    fun runRaw(specJson: String, entry: String, optionsJson: String): String {
        return try {
            val prepared = when (val result = prepare(specJson, entry)) {
                is Prepared.Invalid -> return result.json
                is Prepared.Ready -> result
            }
            val spec = prepared.spec
            val resolvedEntry = prepared.entry
            val options = parseOptions(optionsJson)
            val vision = ScreenshotPipelineVision(
                screenshotProvider = screenshotProvider,
                screenSizeProvider = screenSizeProvider,
                logBus = logBus,
                spec = spec,
                engine = visionEngine,
                customRecognition = ::callRecognition,
            )
            val actions = DevicePipelineActions(
                inputApi = inputApi,
                appApi = appApi,
                shellApi = shellApi,
                logBus = logBus,
                isExitRequested = isExitRequested,
                customAction = ::callAction,
            )
            val runner = PipelineRunner(
                spec = spec,
                vision = vision,
                actions = actions,
                options = options,
                log = { logBus.debug("[pipeline] $it") },
            )
            val result = runner.run(resolvedEntry)
            logBus.info(
                "[pipeline] 结束 entry=$resolvedEntry ok=${result.ok} steps=${result.steps}" +
                    (result.lastNode?.let { " last=$it" } ?: "") +
                    (result.error?.let { " error=$it" } ?: ""),
            )
            result.toJson().toString()
        } catch (t: Throwable) {
            logBus.error("[pipeline] 运行失败: ${t.message}")
            errorJson(t.message ?: t.javaClass.simpleName)
        }
    }

    @JavascriptInterface
    fun replayRaw(recordingId: String, specJson: String, entry: String, optionsJson: String): String {
        return try {
            val store = recordingStore ?: return errorJson("录制存储不可用")
            val recording = store.load(recordingId) ?: return errorJson("找不到录制: $recordingId")
            val firstFrame = recording.events.filterIsInstance<ScreenshotEvent>().firstOrNull()
            val width = recording.meta.width.takeIf { it > 0 } ?: firstFrame?.width ?: 0
            val height = recording.meta.height.takeIf { it > 0 } ?: firstFrame?.height ?: 0
            if (width <= 0 || height <= 0) {
                return errorJson("录制没有可用截图帧: $recordingId")
            }
            val prepared = when (val result = prepare(specJson, entry)) {
                is Prepared.Invalid -> return result.json
                is Prepared.Ready -> result
            }
            val spec = prepared.spec
            val resolvedEntry = prepared.entry

            val optionsObj = runCatching {
                if (optionsJson.isBlank()) null else JSONObject(optionsJson)
            }.getOrNull()
            val replayOptions = ReplayOptions.fromJson(optionsObj)
            val options = parseOptions(optionsJson)

            try {
            val clock = ReplayClock()
            val replay = RecordingReplay(recording.events, replayOptions)
            val vision = ScreenshotPipelineVision(
                screenshotProvider = {
                    replay.nextScreencap()?.also { clock.advance(it.elapsedMs) }?.let(recording::frame)
                },
                screenSizeProvider = { width to height },
                logBus = logBus,
                spec = spec,
                engine = visionEngine,
                customRecognition = ::callRecognition,
            )
            val actions = ReplayPipelineActions(replay, clock) {
                logBus.debug("[pipeline:replay] $it")
            }
            val runner = PipelineRunner(
                spec = spec,
                vision = vision,
                actions = actions,
                options = options,
                log = { logBus.debug("[pipeline:replay] $it") },
                now = clock::now,
            )
            val result = runner.run(resolvedEntry)
            val report = replay.finish()
            val mismatches = report.mismatches.map { it.message }.distinct()
            val notes = report.notes.distinct()
            val json = result.toJson()
            json.put(
                "recording",
                JSONObject()
                    .put("id", recording.meta.id)
                    .put("name", recording.meta.name)
                    .put("width", width)
                    .put("height", height),
            )
            json.put("mismatches", JSONArray().apply { mismatches.forEach { put(it) } })
            json.put(
                "mismatchDetails",
                JSONArray().apply { report.mismatches.forEach { put(it.toJson()) } },
            )
            json.put("notes", JSONArray().apply { notes.forEach { put(it) } })
            json.put("strict", replayOptions.strict)
            json.put("replay", report.toJson())
            if (replayOptions.strict && !report.ok) {
                json.put("ok", false)
                json.put("error", "回放校验失败: ${mismatches.firstOrNull().orEmpty()}")
            }
            logBus.info(
                "[pipeline:replay] id=$recordingId ok=${json.optBoolean("ok")} " +
                    "steps=${result.steps} mismatches=${mismatches.size}",
            )
            json.toString()
            } finally {
                recording.clearFrameCache()
            }
        } catch (t: Throwable) {
            logBus.error("[pipeline:replay] 失败: ${t.message}")
            errorJson(t.message ?: "回放失败")
        }
    }

    private sealed interface Prepared {
        data class Ready(val spec: PipelineSpec, val entry: String) : Prepared
        data class Invalid(val json: String) : Prepared
    }

    private fun prepare(specJson: String, entry: String): Prepared {
        val spec = try {
            PipelineParser.parseDocument(JSONObject(specJson))
        } catch (t: Throwable) {
            return Prepared.Invalid(errorJson(t.message ?: "Pipeline 解析失败"))
        }
        val issues = PipelineValidator.validate(spec)
        val errors = issues.filter { it.severity == PipelineIssueSeverity.Error }
        if (errors.isNotEmpty()) {
            return Prepared.Invalid(errorJson("Pipeline 校验失败: ${errors.first().message}", issues))
        }
        val resolvedEntry = entry.ifBlank { spec.nodes.keys.firstOrNull().orEmpty() }
        if (resolvedEntry.isBlank()) return Prepared.Invalid(errorJson("Pipeline 为空"))
        if (spec[resolvedEntry] == null) {
            return Prepared.Invalid(errorJson("入口节点不存在: $resolvedEntry"))
        }
        return Prepared.Ready(spec, resolvedEntry)
    }

    private fun parseOptions(json: String): PipelineRunOptions {
        val tuning = DeveloperTuning.current
        if (json.isBlank()) return defaultOptions(tuning)
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return defaultOptions(tuning)
        return PipelineRunOptions(
            maxSteps = obj.optInt("maxSteps", tuning.pipelineDefaultMaxSteps).coerceAtLeast(1),
            timeoutMs = obj.optLong("timeoutMs", tuning.pipelineDefaultTimeoutSec * 1_000L)
                .coerceAtLeast(0L),
        )
    }

    private fun defaultOptions(tuning: DeveloperSettings): PipelineRunOptions =
        PipelineRunOptions(
            maxSteps = tuning.pipelineDefaultMaxSteps.coerceAtLeast(1),
            timeoutMs = tuning.pipelineDefaultTimeoutSec.coerceAtLeast(0) * 1_000L,
        )

    private fun errorJson(message: String, issues: List<PipelineIssue> = emptyList()): String =
        JsJson.error(message) {
            put("stopped", false)
            put("taskStopped", false)
            put("entry", "")
            put("lastNode", JSONObject.NULL)
            put("steps", 0)
            put("hitCounts", JSONObject())
            if (issues.isNotEmpty()) put("issues", issuesToJson(issues))
        }

    private fun issuesToJson(issues: List<PipelineIssue>): JSONArray {
        val array = JSONArray()
        for (issue in issues) {
            array.put(
                JSONObject()
                    .put("severity", issue.severity.name.lowercase())
                    .put("node", issue.node ?: JSONObject.NULL)
                    .put("message", issue.message),
            )
        }
        return array
    }

    private fun callRecognition(name: String, param: JSONObject, rois: List<Box>): Box? {
        val fn = recognitions[name] ?: return null
        val roisJson = JSONArray().apply {
            rois.forEach { put(JSONArray().put(it.left).put(it.top).put(it.width).put(it.height)) }
        }
        val result = try {
            fn.call(null, JSArray(fn.context).push(param.toString()).push(roisJson.toString()))
        } catch (t: Throwable) {
            logBus.warn("Pipeline 自定义识别 $name 调用失败: ${t.message}")
            null
        }
        return parseBox(result?.toString())
    }

    private fun callAction(name: String, param: JSONObject, box: Box?): Boolean {
        val fn = customActions[name] ?: run {
            logBus.warn("Pipeline 自定义动作未注册: $name")
            return false
        }
        val boxJson = box?.let {
            JSONObject()
                .put("x", it.left)
                .put("y", it.top)
                .put("width", it.width)
                .put("height", it.height)
                .toString()
        } ?: ""
        val result = try {
            fn.call(null, JSArray(fn.context).push(param.toString()).push(boxJson))
        } catch (t: Throwable) {
            logBus.warn("Pipeline 自定义动作 $name 调用失败: ${t.message}")
            null
        }
        return parseActionResult(result?.toString())
    }

    private fun parseBox(text: String?): Box? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty() || value == "null" || value == "undefined") return null
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is JSONArray -> boxFromArray(parsed)
                is JSONObject -> {
                    val array = parsed.optJSONArray("box")
                    if (array != null) {
                        boxFromArray(array)
                    } else {
                        val left = parsed.optInt("left", parsed.optInt("x", -1))
                        val top = parsed.optInt("top", parsed.optInt("y", -1))
                        val right = parsed.optInt("right", left + parsed.optInt("width", parsed.optInt("w", -1)))
                        val bottom = parsed.optInt("bottom", top + parsed.optInt("height", parsed.optInt("h", -1)))
                        if (left >= 0 && top >= 0 && right > left && bottom > top) {
                            Box(left, top, right, bottom)
                        } else {
                            null
                        }
                    }
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun boxFromArray(array: JSONArray): Box? {
        if (array.length() < 4) return null
        val values = IntArray(4) { array.optInt(it, -1) }
        if (values.any { it < 0 } || values[2] <= 0 || values[3] <= 0) return null
        return Box(values[0], values[1], values[0] + values[2], values[1] + values[3])
    }

    private fun parseActionResult(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.isEmpty() || value == "undefined") return true
        return when (value) {
            "true" -> true
            "false", "null" -> false
            else -> runCatching {
                when (val parsed = JSONTokener(value).nextValue()) {
                    is Boolean -> parsed
                    is JSONObject -> parsed.optBoolean("ok", parsed.optBoolean("success", true))
                    else -> true
                }
            }.getOrDefault(false)
        }
    }
}
