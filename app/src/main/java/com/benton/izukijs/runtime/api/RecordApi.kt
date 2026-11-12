package com.benton.izukijs.runtime.api

import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.record.RecordingSession
import com.benton.izukijs.controller.record.RecordingValidator
import com.benton.izukijs.core.log.LogBus
import org.json.JSONArray

class RecordApi(
    private val session: RecordingSession,
    private val logBus: LogBus,
) {

    private var startedHere = false

    @JavascriptInterface
    fun startRaw(name: String): String = try {
        val wasActive = session.isRecording()
        val id = session.start(name)
        if (!wasActive && id != null) startedHere = true
        if (id == null) {
            JsJson.error(if (session.isRecording()) "已有录制进行中" else "没有可录制的控制后端")
        } else {
            JsJson.ok {
                put("id", id)
                put("active", true)
            }
        }
    } catch (t: Throwable) {
        logBus.error("录制启动失败: ${t.message}")
        JsJson.error(t.message ?: "录制启动失败")
    }

    @JavascriptInterface
    fun stopRaw(): String = try {
        val id = session.stop()
        startedHere = false
        if (id == null) {
            JsJson.error("当前没有进行中的录制")
        } else {
            JsJson.ok {
                put("id", id)
                put("active", false)
            }
        }
    } catch (t: Throwable) {
        logBus.error("录制停止失败: ${t.message}")
        JsJson.error(t.message ?: "录制停止失败")
    }

    @JavascriptInterface
    fun activeRaw(): Boolean = session.isRecording()

    @JavascriptInterface
    fun listRaw(): String = try {
        val array = JSONArray()
        session.store.list().forEach { array.put(it.toJson()) }
        array.toString()
    } catch (t: Throwable) {
        logBus.warn("读取录制列表失败: ${t.message}")
        "[]"
    }

    @JavascriptInterface
    fun infoRaw(id: String): String? = session.store.info(id)?.toJson()?.toString()

    @JavascriptInterface
    fun validateRaw(id: String): String = try {
        val reader = session.store.load(id)
        if (reader == null) {
            JsJson.error("录制不存在: $id")
        } else {
            val validation = RecordingValidator.validate(reader)
            JsJson.ok {
                put("id", reader.meta.id)
                put("name", reader.meta.name)
                put("version", validation.version)
                put("eventCount", validation.eventCount)
                put("frameCount", validation.frameCount)
                put("durationMs", validation.durationMs)
                put("issues", org.json.JSONArray().apply { validation.issues.forEach { put(it) } })
            }
        }
    } catch (t: Throwable) {
        logBus.error("录制校验失败: ${t.message}")
        JsJson.error(t.message ?: "录制校验失败")
    }

    @JavascriptInterface
    fun deleteRaw(id: String): Boolean {
        // 正在写入的录制目录不能删除，否则 writer 仍持有已删除路径下的文件句柄。
        if (id == session.currentRecordingId()) return false
        return session.store.delete(id)
    }

    /** 脚本结束时回收本次脚本启动的录制，避免控制器包装泄漏到后续脚本。 */
    fun close() {
        if (!startedHere) return
        startedHere = false
        val id = session.stop()
        if (id != null) logBus.info("脚本结束，录制已自动停止: $id")
    }
}
