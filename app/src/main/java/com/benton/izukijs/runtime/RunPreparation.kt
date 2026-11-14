package com.benton.izukijs.runtime

import com.benton.izukijs.model.EnvText
import com.benton.izukijs.model.ScriptEnvSpec

/**
 * 运行参数合并的纯逻辑：解析脚本 `@env` 默认值，再按
 * 「默认值 < 全局变量 < 脚本变量 < 已保存值 < 本次传入值」合成。
 *
 * 从 [RunPreparer] 抽出后可以独立测试，不依赖仓库、前台服务或虚拟屏。
 */
internal object RunPreparation {

    fun resolveEnv(
        source: String,
        useGlobal: Boolean,
        global: Map<String, String>,
        script: Map<String, String>,
        savedValues: Map<String, String>,
        values: Map<String, String>,
    ): Map<String, String> = EnvText.resolve(
        defaults = ScriptEnvSpec.parse(source).defaults(),
        global = global,
        script = script,
        runtime = savedValues + values,
        useGlobal = useGlobal,
    )
}
