package com.benton.izukijs.runtime.api

import org.json.JSONObject

/** 脚本桥接层统一的 JSON 结果构造，避免各 API 手写 `{ok:false,error}` 时字段不一致。 */
internal object JsJson {

    fun error(message: String, extra: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
            extra()
        }.toString()

    fun ok(extra: JSONObject.() -> Unit = {}): String =
        JSONObject().apply {
            put("ok", true)
            extra()
        }.toString()
}
