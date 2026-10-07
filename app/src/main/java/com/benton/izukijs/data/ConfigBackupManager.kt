package com.benton.izukijs.data

import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.runtime.LogSettings
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.CaptureSettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置备份 / 恢复 / 重置。序列化为 JSON，便于分享或跨设备迁移。
 *
 * 注意：导出的 JSON **包含明文密钥**（AI API Key、OCR 密钥等），请妥善保管。
 * 脚本文件不在此范围内。
 */
class ConfigBackupManager(
    private val aiConfigRepository: AiConfigRepository,
    private val ocrConfigRepository: OcrConfigRepository,
    private val controllerSettingsRepository: ControllerSettingsRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val logSettingsRepository: LogSettingsRepository,
    private val editorSettingsRepository: EditorSettingsRepository,
) {

    fun exportJson(): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("ai", aiToJson(aiConfigRepository.current()))
        root.put("ocr", ocrToJson(ocrConfigRepository.current()))
        root.put("controller", controllerToJson(controllerSettingsRepository.current()))
        root.put("capture", captureToJson(captureSettingsRepository.current()))
        root.put("log", logToJson(logSettingsRepository.current()))
        root.put("editor", editorToJson(editorSettingsRepository.current()))
        return root.toString(2)
    }

    /** 导入配置。逐段容错：某段缺失或非法时保留该段的当前值。 */
    fun importJson(json: String) {
        val root = JSONObject(json)
        root.optJSONObject("ai")?.let { aiConfigRepository.save(aiFromJson(it)) }
        root.optJSONObject("ocr")?.let { ocrConfigRepository.save(ocrFromJson(it)) }
        root.optJSONObject("controller")?.let { controllerSettingsRepository.save(controllerFromJson(it)) }
        root.optJSONObject("capture")?.let { captureSettingsRepository.save(captureFromJson(it)) }
        root.optJSONObject("log")?.let { logSettingsRepository.save(logFromJson(it)) }
        root.optJSONObject("editor")?.let { editorSettingsRepository.save(editorFromJson(it)) }
    }

    fun resetAll() {
        aiConfigRepository.save(AiConfig())
        ocrConfigRepository.save(OcrConfig())
        controllerSettingsRepository.save(ControllerSettings())
        captureSettingsRepository.save(CaptureSettings())
        logSettingsRepository.save(LogSettings())
        editorSettingsRepository.save(EditorSettings())
    }

    // ---- AI ----

    private fun aiToJson(c: AiConfig): JSONObject = JSONObject().apply {
        put("enabled", c.enabled)
        put("baseUrl", c.baseUrl)
        put("apiKey", c.apiKey)
        put("model", c.model)
        put("temperature", c.temperature)
        put("maxTokens", c.maxTokens)
        put("timeoutSec", c.timeoutSec)
        put("stream", c.stream)
        put("systemPrompt", c.systemPrompt)
        put("maxSteps", c.maxSteps)
        put("toolsEnabled", c.toolsEnabled)
        put("allowShell", c.allowShell)
        put("extraHeaderName", c.extraHeaderName)
        put("extraHeaderValue", c.extraHeaderValue)
    }

    private fun aiFromJson(o: JSONObject): AiConfig {
        val base = AiConfig()
        return base.copy(
            enabled = o.optBoolean("enabled", base.enabled),
            baseUrl = o.optString("baseUrl", base.baseUrl),
            apiKey = o.optString("apiKey", base.apiKey),
            model = o.optString("model", base.model),
            temperature = o.optDouble("temperature", base.temperature),
            maxTokens = o.optInt("maxTokens", base.maxTokens),
            timeoutSec = o.optInt("timeoutSec", base.timeoutSec),
            stream = o.optBoolean("stream", base.stream),
            systemPrompt = o.optString("systemPrompt", base.systemPrompt),
            maxSteps = o.optInt("maxSteps", base.maxSteps),
            toolsEnabled = o.optBoolean("toolsEnabled", base.toolsEnabled),
            allowShell = o.optBoolean("allowShell", base.allowShell),
            extraHeaderName = o.optString("extraHeaderName", base.extraHeaderName),
            extraHeaderValue = o.optString("extraHeaderValue", base.extraHeaderValue),
        )
    }

    // ---- OCR ----

    private fun ocrToJson(c: OcrConfig): JSONObject = JSONObject().apply {
        put("mode", c.mode.name)
        put("provider", c.provider.name)
        put("apiKey", c.apiKey)
        put("secretKey", c.secretKey)
        put("endpoint", c.endpoint)
        put("headerName", c.headerName)
        put("headerValue", c.headerValue)
    }

    private fun ocrFromJson(o: JSONObject): OcrConfig {
        val base = OcrConfig()
        return base.copy(
            mode = o.optString("mode", base.mode.name).toEnumOr(base.mode),
            provider = o.optString("provider", base.provider.name).toEnumOr(base.provider),
            apiKey = o.optString("apiKey", base.apiKey),
            secretKey = o.optString("secretKey", base.secretKey),
            endpoint = o.optString("endpoint", base.endpoint),
            headerName = o.optString("headerName", base.headerName),
            headerValue = o.optString("headerValue", base.headerValue),
        )
    }

    // ---- Controller ----

    private fun controllerToJson(c: ControllerSettings): JSONObject = JSONObject().apply {
        c.preferredMode?.let { put("preferredMode", it.name) }
        put(
            "capabilityPreferences",
            JSONObject().apply {
                c.capabilityPreferences.forEach { (capability, mode) ->
                    put(capability.name, mode.name)
                }
            },
        )
        put("disabledModes", JSONArray().apply { c.disabledModes.forEach { put(it.name) } })
    }

    private fun controllerFromJson(o: JSONObject): ControllerSettings {
        val preferred = o.optString("preferredMode", "").toEnumOrNull<ControlMode>()
        val preferences = buildMap {
            val obj = o.optJSONObject("capabilityPreferences") ?: return@buildMap
            obj.keys().forEach { key ->
                val capability = runCatching { Capability.valueOf(key) }.getOrNull() ?: return@forEach
                val mode = obj.optString(key, "").toEnumOrNull<ControlMode>() ?: return@forEach
                put(capability, mode)
            }
        }
        val disabled = buildSet {
            val arr = o.optJSONArray("disabledModes") ?: return@buildSet
            for (i in 0 until arr.length()) {
                arr.optString(i, "").toEnumOrNull<ControlMode>()?.let { add(it) }
            }
        }
        return ControllerSettings(
            preferredMode = preferred,
            capabilityPreferences = preferences,
            disabledModes = disabled,
        )
    }

    // ---- Capture ----

    private fun captureToJson(c: CaptureSettings): JSONObject = JSONObject().apply {
        put("scalePercent", c.scalePercent)
        put("jpegQuality", c.jpegQuality)
    }

    private fun captureFromJson(o: JSONObject): CaptureSettings {
        val base = CaptureSettings()
        return CaptureSettings(
            scalePercent = o.optInt("scalePercent", base.scalePercent),
            jpegQuality = o.optInt("jpegQuality", base.jpegQuality),
        )
    }

    // ---- Log ----

    private fun logToJson(c: LogSettings): JSONObject = JSONObject().apply {
        put("autoClean", c.autoClean)
        put("maxSizeMb", c.maxSizeMb)
        put("retentionDays", c.retentionDays)
        put("minLevel", c.minLevel.name)
    }

    private fun logFromJson(o: JSONObject): LogSettings {
        val base = LogSettings()
        return LogSettings(
            autoClean = o.optBoolean("autoClean", base.autoClean),
            maxSizeMb = o.optInt("maxSizeMb", base.maxSizeMb),
            retentionDays = o.optInt("retentionDays", base.retentionDays),
            minLevel = o.optString("minLevel", base.minLevel.name).toEnumOr(base.minLevel),
        )
    }

    // ---- Editor ----

    private fun editorToJson(c: EditorSettings): JSONObject = JSONObject().apply {
        put("fontSizeSp", c.fontSizeSp)
        put("autoSave", c.autoSave)
        put("consoleHeightDp", c.consoleHeightDp)
    }

    private fun editorFromJson(o: JSONObject): EditorSettings {
        val base = EditorSettings()
        return EditorSettings(
            fontSizeSp = o.optInt("fontSizeSp", base.fontSizeSp),
            autoSave = o.optBoolean("autoSave", base.autoSave),
            consoleHeightDp = o.optInt("consoleHeightDp", base.consoleHeightDp),
        )
    }

    private companion object {
        const val VERSION = 1
    }
}

private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()
