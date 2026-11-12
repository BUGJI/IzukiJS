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
import com.benton.izukijs.data.TileSettingsRepository
import com.benton.izukijs.developer.DeveloperSettingSpecs
import com.benton.izukijs.developer.DeveloperSettings
import com.benton.izukijs.developer.DeveloperSettingsRepository
import com.benton.izukijs.i18n.AppLanguage
import com.benton.izukijs.i18n.LanguageRepository
import com.benton.izukijs.mcp.McpConfigRepository
import com.benton.izukijs.mcp.core.McpBindMode
import com.benton.izukijs.mcp.core.McpConfig
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode
import com.benton.izukijs.model.RunMode
import com.benton.izukijs.ocr.OcrConfig
import com.benton.izukijs.ocr.OcrConfigRepository
import com.benton.izukijs.core.notification.NotificationSettings
import com.benton.izukijs.core.notification.NotificationSettingsCodec
import com.benton.izukijs.notification.NotificationSettingsRepository
import com.benton.izukijs.core.net.HttpSettings
import com.benton.izukijs.runtime.FloatingWindowMode
import com.benton.izukijs.runtime.HttpSettingsRepository
import com.benton.izukijs.runtime.LogSettings
import com.benton.izukijs.runtime.LogSettingsRepository
import com.benton.izukijs.runtime.RuntimeSettings
import com.benton.izukijs.runtime.RuntimeSettingsRepository
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
 * 开发者选项只备份参数值，`unlocked` 入口开关始终保留本机状态。
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
        notification: NotificationSettingsRepository,
        runtime: RuntimeSettingsRepository,
        language: LanguageRepository,
        developer: DeveloperSettingsRepository,
        mcp: McpConfigRepository,
        tile: TileSettingsRepository,
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
        notificationSection(notification),
        runtimeSection(runtime),
        languageSection(language),
        developerSection(developer),
        mcpSection(mcp),
        tileSection(tile),
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

    private fun notificationSection(repository: NotificationSettingsRepository) =
        ConfigSectionAdapter(
            key = "notification",
            current = repository::current,
            save = repository::save,
            defaultValue = NotificationSettings(),
            encode = NotificationSettingsCodec::encode,
            decode = { o, base -> NotificationSettingsCodec.decode(o, base) },
        )

    private fun runtimeSection(repository: RuntimeSettingsRepository) = ConfigSectionAdapter(
        key = "runtime",
        current = repository::current,
        save = repository::save,
        defaultValue = RuntimeSettings(),
        encode = { c ->
            JSONObject().apply {
                put("keepAwake", c.keepAwake)
                put("wakeScreen", c.wakeScreen)
                put("appList", c.appList)
                put("progressNotification", c.progressNotification)
                put("tileEnabled", c.tileEnabled)
                put("scriptTimeoutMinutes", c.scriptTimeoutMinutes)
                put("perfBaseline", c.perfBaseline)
                put("floatingWindowMode", c.floatingWindowMode.key)
            }
        },
        decode = { o, base ->
            RuntimeSettings(
                keepAwake = o.optBoolean("keepAwake", base.keepAwake),
                wakeScreen = o.optBoolean("wakeScreen", base.wakeScreen),
                appList = o.optBoolean("appList", base.appList),
                progressNotification = o.optBoolean(
                    "progressNotification",
                    base.progressNotification,
                ),
                tileEnabled = o.optBoolean("tileEnabled", base.tileEnabled),
                scriptTimeoutMinutes = o.optInt("scriptTimeoutMinutes", base.scriptTimeoutMinutes)
                    .coerceAtLeast(0),
                perfBaseline = o.optBoolean("perfBaseline", base.perfBaseline),
                floatingWindowMode = FloatingWindowMode.fromKey(
                    o.optString("floatingWindowMode", base.floatingWindowMode.key),
                ),
            )
        },
    )

    private fun languageSection(repository: LanguageRepository) = ConfigSectionAdapter(
        key = "language",
        current = repository::current,
        save = repository::save,
        defaultValue = AppLanguage.SYSTEM,
        encode = { c ->
            JSONObject().apply {
                put("value", c.name)
                put("tag", c.tag ?: JSONObject.NULL)
            }
        },
        decode = { o, base ->
            val byName = AppLanguage.entries.firstOrNull { it.name == o.optString("value", "") }
            val byTag = if (o.has("tag") && !o.isNull("tag")) {
                AppLanguage.fromTag(o.optString("tag"))
            } else {
                null
            }
            byName ?: byTag ?: base
        },
    )

    private fun developerSection(repository: DeveloperSettingsRepository) = ConfigSectionAdapter(
        key = "developer",
        current = repository::current,
        save = repository::save,
        defaultValue = DeveloperSettings(),
        encode = { c ->
            JSONObject().apply {
                DeveloperSettingSpecs.all.forEach { spec -> put(spec.key, spec.get(c)) }
            }
        },
        decode = { o, base ->
            var result = base.copy(unlocked = repository.current().unlocked)
            DeveloperSettingSpecs.all.forEach { spec ->
                result = spec.set(result, o.optInt(spec.key, spec.default))
            }
            result
        },
        resetValue = { DeveloperSettings(unlocked = repository.current().unlocked) },
    )

    private fun mcpSection(repository: McpConfigRepository) = ConfigSectionAdapter(
        key = "mcp",
        current = repository::current,
        save = repository::save,
        defaultValue = McpConfig(),
        encode = { c ->
            JSONObject().apply {
                put("enabled", c.enabled)
                put("port", c.port)
                put("bindMode", c.bindMode.name)
                // 访问令牌不写入备份：备份文件可能被同步 / 分享；导入时保留本机令牌。
                put("allowShell", c.allowShell)
                put("allowScripts", c.allowScripts)
                put("allowUnlock", c.allowUnlock)
                put("allowRecording", c.allowRecording)
                put("autoUnlock", c.autoUnlock)
                put("allowDangerousShell", c.allowDangerousShell)
                put("blockedPackages", JSONArray().apply { c.blockedPackages.forEach { put(it) } })
            }
        },
        decode = { o, base ->
            McpConfig(
                enabled = o.optBoolean("enabled", base.enabled),
                port = o.optInt("port", base.port)
                    .coerceIn(McpConfig.MIN_PORT, McpConfig.MAX_PORT),
                bindMode = runCatching {
                    McpBindMode.valueOf(o.optString("bindMode", base.bindMode.name))
                }.getOrDefault(base.bindMode),
                token = if (o.has("token") && !o.isNull("token")) {
                    o.optString("token")
                } else {
                    repository.current().token
                },
                allowShell = o.optBoolean("allowShell", base.allowShell),
                allowScripts = o.optBoolean("allowScripts", base.allowScripts),
                allowUnlock = o.optBoolean("allowUnlock", base.allowUnlock),
                allowRecording = o.optBoolean("allowRecording", base.allowRecording),
                autoUnlock = o.optBoolean("autoUnlock", base.autoUnlock),
                allowDangerousShell = o.optBoolean(
                    "allowDangerousShell",
                    base.allowDangerousShell,
                ),
                blockedPackages = if (o.has("blockedPackages")) {
                    readStringList(o, "blockedPackages")
                } else {
                    base.blockedPackages
                },
            )
        },
    )

    private fun tileSection(repository: TileSettingsRepository) = ConfigSectionAdapter<String?>(
        key = "tile",
        current = repository::current,
        save = repository::save,
        defaultValue = null,
        encode = { id -> JSONObject().put("scriptId", id ?: JSONObject.NULL) },
        decode = { o, _ ->
            if (o.isNull("scriptId")) {
                null
            } else {
                o.optString("scriptId", "").takeIf { it.isNotBlank() }
            }
        },
    )

    private fun readStringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index, "").trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }

    private fun virtualScreenSection(repository: VirtualScreenSettingsRepository) = ConfigSectionAdapter(
        key = "virtualScreen",
        current = repository::current,
        save = repository::save,
        defaultValue = repository.defaultSettings(),
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
    private val resetValue: (T) -> T = { defaultValue },
) : ConfigSection {

    override fun export(): JSONObject = encode(current())

    // 以当前值为基线解码：旧备份/部分备份缺失的字段保留现状，而不是回退到默认值。
    override fun import(value: JSONObject) = save(decode(value, current()))

    override fun reset() = save(resetValue(current()))
}

private inline fun <reified T : Enum<T>> String.toEnumOr(fallback: T): T =
    runCatching { enumValueOf<T>(this) }.getOrDefault(fallback)

private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
    runCatching { enumValueOf<T>(this) }.getOrNull()
