package com.benton.izukijs.backup

import org.json.JSONObject

/**
 * 配置备份中的一组配置项。
 *
 * 每个 section 自带模型的 JSON 编解码与重置逻辑，新增配置时只需实现本接口
 * （或在 [ConfigSections.all] 里注册一个适配器），无需再改
 * [ConfigBackupManager] 的导出 / 导入 / 重置三处。
 */
interface ConfigSection {

    /** 在备份 JSON 中的键名。 */
    val key: String

    /** 导出当前配置。 */
    fun export(): JSONObject

    /** 从备份恢复；缺失字段由具体实现回退到默认值，保证旧备份可导入。 */
    fun import(value: JSONObject)

    /** 恢复默认值。 */
    fun reset()
}
