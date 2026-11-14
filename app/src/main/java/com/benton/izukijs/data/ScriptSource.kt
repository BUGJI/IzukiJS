package com.benton.izukijs.data

import com.benton.izukijs.model.ScriptInfo

/** 编排 / 定时执行器读取脚本所需的最小接口。 */
interface ScriptSource {
    fun find(id: String): ScriptInfo?

    fun read(script: ScriptInfo): String
}
