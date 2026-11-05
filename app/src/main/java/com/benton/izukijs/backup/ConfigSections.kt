package com.benton.izukijs.backup

import com.benton.izukijs.ai.AiConfig
import com.benton.izukijs.ai.AiConfigRepository
import com.benton.izukijs.controller.ControllerSettings
import com.benton.izukijs.controller.ControllerSettingsRepository
import com.benton.izukijs.data.AppearanceRepository
import com.benton.izukijs.data.AppearanceSettings
import com.benton.izukijs.data.EditorSettings
import com.benton.izukijs.data.EditorSettingsRepository
import com.benton.izukijs.data.GlobalEnvRepository
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.runtime.HttpSettings
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.runtime.LogSettings
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.CaptureSettingsRepository
import com.benton.izukijs.update.UpdateSettings
import com.benton.izukijs.update.UpdateSettingsRepository
import com.benton.izukijs.virtualscreen.VirtualScreenResolution
import com.benton.izukijs.virtualscreen.VirtualScreenSettings
import com.benton.izukijs.virtualscreen.VirtualScreenSettingsRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * 全部可备份配置项的注册表。
 *
 * 锁屏解锁设置（PIN / 图案）使用设备绑定的 Keystore 加密，无法跨设备迁移，
 * 因此不出现在这里；它由 [ConfigBackupManager] 的 resetOnly 回调单独清理。
 */
object ConfigSections {

    fun all(
        ai: AiConfigRepository,
        ocr: OcrConfigRepository,
        controller: ControllerSettingsRepository,
        capture: CaptureSettingsRepository,
        log: LogSettingsRepository,
        http: HttpSettingsRepository,
        editor: EditorSettingsRepository,
        appearance: AppearanceRepository,
        update: UpdateSettingsRepository,
        globalEnv: GlobalEnvRepository,
        virtualScreen: VirtualScreenSettingsRepository,
    ): List<ConfigSection> = listOf(
        aiSection(ai),
        ocrSection(ocr),
        controllerSection(controller),
        captureSection(capture),
        logSection(log),
        httpSection(http),
        editorSection(editor),
        appearanceSection(appearance),
        updateSection(update),
        globalEnvSection(globalEnv),
        virtualScreenSection(virtualScreen),
    )

    private fun aiSection(repository: AiConfigRepository) = ConfigSectionAdapter(
        key = "ai",
        current = repository::current,
        save = repository::save,
        defaultValue = AiConfig(),
        encode = { c ->
            JSONObject().apply {
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
        },
        decode = { o, base ->
            base.copy(
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
        },
    )

    private fun ocrSection(repository: OcrConfigRepository) = ConfigSectionAdapter(
        key = "ocr",
        current = repository::current,
        save = repository::save,
        defaultValue = OcrConfig(),
        encode = { c ->
            JSONObject().apply {
                put("mode", c.mode.name)
                put("provider", c.provider.name)
                put("apiKey", c.apiKey)
                put("secretKey", c.secretKey)
                put("endpoint", c.endpoint)
                put("headerName", c.headerName)
                put("headerValue", c.headerValue)
            }
        },
        decode = { o, base ->
            base.copy(
                mode = o.optString("mode", base.mode.name).toEnumOr(base.mode),
                provider = o.optString("provider", base.provider.name).toEnumOr(base.provider),
                apiKey = o.optString("apiKey", base.apiKey),
                secretKey = o.optString("secretKey", base.secretKey),
                endpoint = o.optString("endpoint", base.endpoint),
                headerName = o.optString("headerName", base.headerName),
                headerValue = o.optString("headerValue", base.headerValue),
            )
        },
    )

    private fun controllerSection(repository: ControllerSettingsRepository) = ConfigSectionAdapter(
        key = "controller",
        current = repository::current,
        save = repository::save,
        defaultValue = ControllerSettings(),
        encode = { c ->
            JSONObject().apply {
                c.readMode?.let { put("readMode", it.name) }
                c.operateMode?.let { put("operateMode", it.name) }
                put(
                    "capabilityPreferences",
                    JSONObject().apply {
                        c.capabilityPreferences.forEach { (capability, mode) ->
                            put(capability.name, mode.name)
                        }
                    },
                )
                put(
                    "disabledModes",
                    JSONArray().apply { c.disabledModes.forEach { put(it.name) } },
                )
            }
        },
        decode = { o, _ ->
            // 兼容旧备份：单一 preferredMode 同时作为读取 / 操作两档的初始值。
            val legacy = o.optString("preferredMode", "").toEnumOrNull<ControlMode>()
            val read = o.optString("readMode", "").toEnumOrNull<ControlMode>() ?: legacy
            val operate = o.optString("operateMode", "").toEnumOrNull<ControlMode>() ?: legacy
            val preferences = buildMap {
                val obj = o.optJSONObject("capabilityPreferences") ?: return@buildMap
                obj.keys().forEach { key ->
                    val capability = runCatching { Capability.valueOf(key) }.getOrNull()
                        ?: return@forEach
                    val mode = obj.optString(key, "").toEnumOrNull<ControlMode>()
                        ?: return@forEach
                    put(capability, mode)
                }
            }
            val disabled = buildSet {
                val arr = o.optJSONArray("disabledModes") ?: return@buildSet
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").toEnumOrNull<ControlMode>()?.let { add(it) }
                }
            }
            ControllerSettings(
                readMode = read,
                operateMode = operate,
                capabilityPreferences = preferences,
                disabledModes = disabled,
            )
        },
    )

    private fun captureSection(repository: CaptureSettingsRepository) = ConfigSectionAdapter(
        key = "capture",
        current = repository::current,
        save = repository::save,
        defaultValue = CaptureSettings(),
        encode = { c ->
            JSONObject().apply {
                put("scalePercent", c.scalePercent)
                put("jpegQuality", c.jpegQuality)
            }
        },
        decode = { o, base ->
            CaptureSettings(
                scalePercent = o.optInt("scalePercent", base.scalePercent),
                jpegQuality = o.optInt("jpegQuality", base.jpegQuality),
            )
        },
    )

    private fun logSection(repository: LogSettingsRepository) = ConfigSectionAdapter(
        key = "log",
        current = repository::current,
        save = repository::save,
        defaultValue = LogSettings(),
        encode = { c ->
            JSONObject().apply {
                put("autoClean", c.autoClean)
                put("maxSizeMb", c.maxSizeMb)
                put("retentionDays", c.retentionDays)
                put("minLevel", c.minLevel.name)
            }
        },
        decode = { o, base ->
            LogSettings(
                autoClean = o.optBoolean("autoClean", base.autoClean),
                maxSizeMb = o.optInt("maxSizeMb", base.maxSizeMb),
                retentionDays = o.optInt("retentionDays", base.retentionDays),
                minLevel = o.optString("minLevel", base.minLevel.name).toEnumOr(base.minLevel),
            )
        },
    )

    private fun httpSection(repository: HttpSettingsRepository) = ConfigSectionAdapter(
        key = "http",
        current = repository::current,
        save = repository::save,
        defaultValue = HttpSettings(),
        encode = { c ->
            JSONObject().apply {
                put("enabled", c.enabled)
                put("allowlist", c.allowlist)
            }
        },
        decode = { o, base ->
            HttpSettings(
                enabled = o.optBoolean("enabled", base.enabled),
                allowlist = o.optString("allowlist", base.allowlist),
            )
        },
    )

    private fun editorSection(repository: EditorSettingsRepository) = ConfigSectionAdapter(
        key = "editor",
        current = repository::current,
        save = repository::save,
        defaultValue = EditorSettings(),
        encode = { c ->
            JSONObject().apply {
                put("fontSizeSp", c.fontSizeSp)
                put("autoSave", c.autoSave)
                put("consoleHeightDp", c.consoleHeightDp)
            }
        },
        decode = { o, base ->
            EditorSettings(
                fontSizeSp = o.optInt("fontSizeSp", base.fontSizeSp),
                autoSave = o.optBoolean("autoSave", base.autoSave),
                consoleHeightDp = o.optInt("consoleHeightDp", base.consoleHeightDp),
            )
        },
    )

    private fun appearanceSection(repository: AppearanceRepository) = ConfigSectionAdapter(
        key = "appearance",
        current = repository::current,
        save = repository::save,
        defaultValue = AppearanceSettings(),
        encode = { c ->
            JSONObject().apply {
                put("themeMode", c.themeMode.name)
                put("dynamicColor", c.dynamicColor)
                put("amoledBackground", c.amoledBackground)
                c.seedArgb?.let { put("seedArgb", it) }
            }
        },
        decode = { o, base ->
            AppearanceSettings(
                themeMode = o.optString("themeMode", base.themeMode.name).toEnumOr(base.themeMode),
                dynamicColor = o.optBoolean("dynamicColor", base.dynamicColor),
                amoledBackground = o.optBoolean("amoledBackground", base.amoledBackground),
                seedArgb = if (o.has("seedArgb")) o.optInt("seedArgb") else base.seedArgb,
            )
        },
    )

    private fun updateSection(repository: UpdateSettingsRepository) = ConfigSectionAdapter(
        key = "update",
        current = repository::current,
        save = repository::save,
        defaultValue = UpdateSettings(),
        encode = { c ->
            JSONObject().apply {
                put("autoCheck", c.autoCheck)
                put("method", c.method.name)
            }
        },
        decode = { o, base ->
            UpdateSettings(
                autoCheck = o.optBoolean("autoCheck", base.autoCheck),
                method = o.optString("method", base.method.name).toEnumOr(base.method),
            )
        },
    )

    private fun globalEnvSection(repository: GlobalEnvRepository) =
        ConfigSectionAdapter<Map<String, String>>(
            key = "globalEnv",
            current = repository::current,
            save = repository::save,
            defaultValue = emptyMap(),
            encode = { values -> JSONObject(values as Map<*, *>) },
            decode = { o, _ ->
                buildMap {
                    o.keys().forEach { key -> put(key, o.optString(key, "")) }
                }
            },
        )

    private fun virtualScreenSection(repository: VirtualScreenSettingsRepository) = ConfigSectionAdapter(
        key = "virtualScreen",
        current = repository::current,
        save = repository::save,
        defaultValue = VirtualScreenSettings(),
        encode = { c ->
            JSONObject().apply {
                put(
                    "allowedModes",
                    JSONArray().apply { c.allowedModes.forEach { put(it.name) } },
                )
                put("defaultMode", c.defaultMode.name)
                put("width", c.resolution.width)
                put("height", c.resolution.height)
                put("dpi", c.resolution.dpi)
                put("pipOnHome", c.pipOnHome)
                put("autoStopAfterRun", c.autoStopAfterRun)
                put("showSystemDecorations", c.showSystemDecorations)
            }
        },
        decode = { o, base ->
            val allowed = buildSet {
                val arr = o.optJSONArray("allowedModes") ?: return@buildSet
                for (i in 0 until arr.length()) {
                    RunMode.from(arr.optString(i, ""))?.let { add(it) }
                }
            }.ifEmpty { base.allowedModes }
            VirtualScreenSettings(
                allowedModes = allowed,
                defaultMode = RunMode.from(o.optString("defaultMode", "")) ?: base.defaultMode,
                resolution = VirtualScreenResolution(
                    width = o.optInt("width", base.resolution.width),
                    height = o.optInt("height", base.resolution.height),
                    dpi = o.optInt("dpi", base.resolution.dpi),
                ),
                pipOnHome = o.optBoolean("pipOnHome", base.pipOnHome),
                autoStopAfterRun = o.optBoolean("autoStopAfterRun", base.autoStopAfterRun),
                showSystemDecorations = o.optBoolean(
                    "showSystemDecorations",
                    base.showSystemDecorations,
                ),
            ).normalized()
        },
    )
}

/** 通用的「读取当前值 → 编码 / 解码 → 保存」适配器。 */
private class ConfigSectionAdapter<T>(
    override val key: String,
    private val current: () -> T,
    private val save: (T) -> Unit,
    private val defaultValue: T,
    private val encode: (T) -> JSONObject,
    private val decode: (JSONObject, T) -> T,
) : ConfigSection {

    override fun export(): JSONObject = encode(current())

    override fun import(value: JSONObject) = save(decode(value, defaultValue))

    override fun reset() = save(defaultValue)
}

private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()
