package com.benton.izukijs.runtime.api

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray

/**
 * Auto.js `storages` 的持久化后端。
 *
 * 每个 store 使用独立的 SharedPreferences；值由 JS 侧 JSON 序列化，
 * 因此这里只处理字符串，不把任意 JS 对象带进 Kotlin。
 */
class StorageApi(private val context: Context) {

    @JavascriptInterface
    fun getRaw(store: String, key: String): String? = prefs(store).getString(key, null)

    @JavascriptInterface
    fun putRaw(store: String, key: String, value: String): Boolean {
        if (key.isBlank()) return false
        return prefs(store).edit().putString(key, value).commit()
    }

    @JavascriptInterface
    fun removeRaw(store: String, key: String): Boolean =
        prefs(store).edit().remove(key).commit()

    @JavascriptInterface
    fun containsRaw(store: String, key: String): Boolean = prefs(store).contains(key)

    @JavascriptInterface
    fun clearRaw(store: String): Boolean = prefs(store).edit().clear().commit()

    @JavascriptInterface
    fun keysRaw(store: String): String {
        val keys = prefs(store).all.keys.sorted()
        return JSONArray().apply { keys.forEach { put(it) } }.toString()
    }

    private fun prefs(store: String) = context.getSharedPreferences(
        storagePrefsName(store),
        Context.MODE_PRIVATE,
    )

    companion object {
        private const val PREFIX = "izuki_storage_"
        private const val MAX_NAME_LENGTH = 64

        /** 暴露给 JVM 测试；避免把 store 名直接拼进偏好文件名。 */
        internal fun storagePrefsName(store: String): String {
            val safe = store.trim()
                .ifBlank { "default" }
                .map { ch -> if (ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.') ch else '_' }
                .joinToString("")
                .take(MAX_NAME_LENGTH)
                .ifBlank { "default" }
            return PREFIX + safe
        }
    }
}
