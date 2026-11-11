package com.benton.izukijs.backup

import org.json.JSONObject

/**
 * 配置备份 / 恢复 / 重置。序列化为 JSON，便于分享或跨设备迁移。
 *
 * 具体配置项由 [ConfigSection] 注册表提供；新增配置只需在 [ConfigSections.all]
 * 注册一个 section，导出 / 导入 / 重置都会自动包含，不会再出现漏改某一处的情况。
 *
 * 注意：导出的 JSON **包含明文密钥**（AI API Key、OCR 密钥、通知渠道 token 等），请妥善保管。
 * 脚本文件不在此范围内。
 *
 * [resetOnly] 用于只参与重置、不参与备份的配置（例如使用设备绑定 Keystore
 * 加密、无法跨设备迁移的锁屏 PIN / 图案）。
 */
class ConfigBackupManager(
    private val sections: List<ConfigSection>,
    private val resetOnly: List<() -> Unit> = emptyList(),
) {

    fun exportJson(): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        sections.forEach { root.put(it.key, it.export()) }
        return root.toString(2)
    }

    /** 导入配置。某段缺失时保留该段的当前值；字段非法时按默认值回退。 */
    fun importJson(json: String) {
        val root = JSONObject(json)
        sections.forEach { section ->
            root.optJSONObject(section.key)?.let { section.import(it) }
        }
    }

    fun resetAll() {
        sections.forEach { it.reset() }
        resetOnly.forEach { it() }
    }

    private companion object {
        const val VERSION = 1
    }
}
