package com.benton.izukijs.runtime.api

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.webkit.JavascriptInterface
import android.widget.EditText
import com.benton.izukijs.core.app.CurrentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/**
 * Auto.js `dialogs` 的阻塞式实现。
 *
 * 脚本线程等待 [CountDownLatch]，真正的 View 操作全部投递到 Activity 主线程。
 * 后台虚拟屏 / 无 Activity 时返回 `no-activity`，由 JS 侧降级为 null。
 */
class DialogApi(
    private val context: Context,
    private val isExitRequested: () -> Boolean = { false },
) {

    @JavascriptInterface
    fun showRaw(type: String, optionsJson: String, timeoutMs: Double): String {
        val activity = CurrentActivity.get() ?: context.findActivity()
            ?: return errorJson("no-activity")
        if (activity.isFinishing || activity.isDestroyed) return errorJson("activity-not-ready")
        val options = runCatching { JSONObject(optionsJson.ifBlank { "{}" }) }
            .getOrElse { JSONObject() }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val dialogRef = AtomicReference<AlertDialog?>(null)

        activity.runOnUiThread {
            try {
                val dialog = buildDialog(activity, type, options, result, latch)
                dialogRef.set(dialog)
                dialog.setOnCancelListener {
                    settle(result, latch) { resultJson("cancel") }
                }
                dialog.setOnDismissListener {
                    settle(result, latch) { resultJson("dismiss") }
                }
                dialog.show()
            } catch (t: Throwable) {
                settle(result, latch) { errorJson(t.message ?: "dialog-failed") }
            }
        }

        val timeout = timeoutMs.toLong()
        val deadline = if (timeout > 0) System.currentTimeMillis() + timeout else Long.MAX_VALUE
        while (true) {
            if (isExitRequested()) {
                dismiss(activity, dialogRef)
                return resultJson("cancel")
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                dismiss(activity, dialogRef)
                return resultJson("timeout")
            }
            val wait = if (deadline == Long.MAX_VALUE) 200L else minOf(200L, remaining)
            if (latch.await(wait, TimeUnit.MILLISECONDS)) {
                return result.get() ?: errorJson("empty-result")
            }
        }
    }

    private fun buildDialog(
        activity: Activity,
        type: String,
        options: JSONObject,
        result: AtomicReference<String?>,
        latch: CountDownLatch,
    ): AlertDialog {
        val builder = AlertDialog.Builder(activity)
        options.optString("title").takeIf { it.isNotBlank() }?.let(builder::setTitle)
        options.optString("message").takeIf { it.isNotBlank() }?.let(builder::setMessage)
        builder.setCancelable(options.optBoolean("cancelable", true))

        val positive = options.optString("positive")
            .ifBlank { activity.getString(android.R.string.ok) }
        val negative = options.optString("negative")
            .ifBlank { activity.getString(android.R.string.cancel) }
        val neutral = options.optString("neutral").takeIf { it.isNotBlank() }

        when (type) {
            "alert" -> builder.setPositiveButton(positive) { _, _ ->
                settle(result, latch) { resultJson("positive") }
            }

            "confirm" -> {
                builder.setPositiveButton(positive) { _, _ ->
                    settle(result, latch) { resultJson("positive") }
                }
                builder.setNegativeButton(negative) { _, _ ->
                    settle(result, latch) { resultJson("negative") }
                }
            }

            "prompt" -> {
                val edit = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    setText(options.optString("defaultValue"))
                    setSelection(text.length)
                    setSingleLine(false)
                }
                builder.setView(edit)
                builder.setPositiveButton(positive) { _, _ ->
                    settle(result, latch) {
                        resultJson("positive", text = edit.text?.toString().orEmpty())
                    }
                }
                builder.setNegativeButton(negative) { _, _ ->
                    settle(result, latch) { resultJson("negative") }
                }
            }

            "select" -> {
                val items = options.optJSONArray("items") ?: JSONArray()
                val labels = Array(items.length()) { items.optString(it) }
                builder.setItems(labels) { _, index ->
                    settle(result, latch) {
                        resultJson("positive", index = index)
                    }
                }
                builder.setNegativeButton(negative) { _, _ ->
                    settle(result, latch) { resultJson("negative") }
                }
            }

            "multiChoice" -> {
                val items = options.optJSONArray("items") ?: JSONArray()
                val labels = Array(items.length()) { items.optString(it) }
                val checked = BooleanArray(labels.size)
                val selected = options.optJSONArray("selectedIndices")
                if (selected != null) {
                    for (index in 0 until selected.length()) {
                        val value = selected.optInt(index, -1)
                        if (value in checked.indices) checked[value] = true
                    }
                }
                builder.setMultiChoiceItems(labels, checked) { _, index, value ->
                    checked[index] = value
                }
                builder.setPositiveButton(positive) { _, _ ->
                    settle(result, latch) {
                        val indices = JSONArray()
                        checked.forEachIndexed { index, value -> if (value) indices.put(index) }
                        resultJson("positive", indices = indices)
                    }
                }
                builder.setNegativeButton(negative) { _, _ ->
                    settle(result, latch) { resultJson("negative") }
                }
            }

            else -> return builder.setMessage("不支持的对话框类型: $type")
                .setPositiveButton(positive) { _, _ ->
                    settle(result, latch) { errorJson("unsupported-type") }
                }
                .create()
        }

        neutral?.let { label ->
            builder.setNeutralButton(label) { _, _ ->
                settle(result, latch) { resultJson("neutral") }
            }
        }
        return builder.create()
    }

    private fun settle(
        result: AtomicReference<String?>,
        latch: CountDownLatch,
        value: () -> String,
    ) {
        if (result.compareAndSet(null, value())) {
            latch.countDown()
        }
    }

    private fun dismiss(activity: Activity, dialogRef: AtomicReference<AlertDialog?>) {
        val dialog = dialogRef.getAndSet(null) ?: return
        activity.runOnUiThread {
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
    }

    private fun resultJson(
        action: String,
        text: String? = null,
        index: Int = -1,
        indices: JSONArray? = null,
    ): String = JSONObject().apply {
        put("ok", action != "error")
        put("action", action)
        if (text != null) put("text", text)
        if (index >= 0) put("index", index)
        if (indices != null) put("indices", indices)
    }.toString()

    private fun errorJson(message: String): String = JSONObject().apply {
        put("ok", false)
        put("action", "error")
        put("error", message)
    }.toString()

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
