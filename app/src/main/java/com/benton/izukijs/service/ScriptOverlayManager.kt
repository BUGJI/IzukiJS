package com.benton.izukijs.service

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 脚本悬浮窗管理。
 *
 * 复用 [ScriptForegroundService] 的进程保活，不额外启动前台服务。窗口内容用 JSON
 * 描述，按钮 / 开关只把动作写入队列，脚本通过 `floaty.waitAction` 消费；不跨线程
 * 回调 QuickJS。
 */
object ScriptOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windows = ConcurrentHashMap<String, Window>()

    /** 脚本线程等待主线程完成窗口操作的最长时间；主线程卡死时不至于拖死脚本。 */
    private const val MAIN_THREAD_TIMEOUT_MS = 3_000L

    @Volatile
    private var appContext: Context? = null

    /** 当前持有悬浮窗的服务实例；applicationContext 相同，必须用实例区分新旧服务。 */
    @Volatile
    private var attachedOwner: Any? = null

    fun attach(context: Context, owner: Any) {
        appContext = context.applicationContext
        attachedOwner = owner
    }

    /** 只允许当前服务实例清理，避免旧服务 onDestroy 关掉新 run 的悬浮窗。 */
    fun detach(context: Context, owner: Any) {
        if (attachedOwner !== owner) return
        if (appContext !== context.applicationContext) return
        closeAll(null)
        appContext = null
        attachedOwner = null
    }

    fun available(): Boolean {
        val context = appContext ?: return false
        return Settings.canDrawOverlays(context)
    }

    /**
     * 创建并显示悬浮窗。
     *
     * View 的创建与 `WindowManager.addView` 都必须在主线程执行；脚本运行在 QuickJS
     * 线程，因此这里同步投递到主线程并等待结果。返回值与失败语义保持不变。
     */
    fun show(runId: String?, optionsJson: String): String {
        val context = appContext ?: return error("悬浮窗服务未运行")
        if (!Settings.canDrawOverlays(context)) {
            return error("未授予悬浮窗权限")
        }
        val options = runCatching { JSONObject(optionsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return error("悬浮窗参数不是合法 JSON")
        val id = options.optString("id").trim().ifBlank { "floaty-${System.nanoTime()}" }
        return runCatching {
            callOnMain {
                if (windows.containsKey(id)) {
                    error("悬浮窗 id 已存在: $id")
                } else {
                    val window = Window(context, runId, id, options)
                    try {
                        window.show()
                    } catch (t: Throwable) {
                        window.close()
                        throw t
                    }
                    windows[id] = window
                    JSONObject().apply {
                        put("ok", true)
                        put("id", id)
                    }.toString()
                }
            }
        }.getOrElse {
            error("创建悬浮窗失败: ${it.message}")
        }
    }

    fun update(runId: String?, id: String, optionsJson: String): Boolean {
        val window = windows[id] ?: return false
        if (runId != null && window.runId != null && window.runId != runId) return false
        val options = runCatching { JSONObject(optionsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return false
        return runCatching {
            callOnMain {
                window.update(options)
                true
            }
        }.getOrDefault(false)
    }

    fun close(runId: String?, id: String): Boolean {
        val window = windows[id] ?: return false
        if (runId != null && window.runId != null && window.runId != runId) return false
        if (!windows.remove(id, window)) return false
        runCatching { runOnMain { window.close() } }
        return true
    }

    fun closeAll(runId: String?) {
        val targets = windows.values.filter { runId == null || it.runId == null || it.runId == runId }
        if (targets.isEmpty()) return
        targets.forEach { windows.remove(it.id, it) }
        runCatching { runOnMain { targets.forEach { it.close() } } }
    }

    fun actions(id: String): String {
        val window = windows[id] ?: return "[]"
        return window.drainActions().toString()
    }

    fun pollAction(id: String): String? = windows[id]?.pollAction()?.toString()

    /** 窗口位置 / 尺寸 / 子 View 均只在主线程读写，这里同步取一次快照。 */
    fun status(id: String): String? =
        runCatching { callOnMainOrNull { windows[id]?.status()?.toString() } }.getOrNull()

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun runOnMain(block: () -> Unit) {
        if (isMainThread()) {
            block()
            return
        }
        callOnMain {
            block()
            Unit
        }
    }

    private fun <T : Any> callOnMainOrNull(block: () -> T?): T? {
        if (isMainThread()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        mainHandler.post {
            try {
                value = block()
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("主线程响应超时")
        }
        failure?.let { throw it }
        return value
    }

    private fun <T : Any> callOnMain(block: () -> T): T =
        callOnMainOrNull(block) ?: throw IllegalStateException("主线程操作没有返回结果")

    private fun error(message: String): String = JSONObject().apply {
        put("ok", false)
        put("error", message)
    }.toString()

    private class Window(
        private val context: Context,
        val runId: String?,
        val id: String,
        private val options: JSONObject,
    ) {
        private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val params = WindowManager.LayoutParams(
            options.optInt("width", WindowManager.LayoutParams.WRAP_CONTENT),
            options.optInt("height", WindowManager.LayoutParams.WRAP_CONTENT),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flagsOf(options),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = options.optInt("x", 0)
            y = options.optInt("y", 0)
        }
        private val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = options.optDouble("cornerRadius", 24.0).toFloat()
                setColor(colorOf(options.optString("background"), Color.parseColor("#CC202124")))
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
        }
        private val views = ConcurrentHashMap<String, View>()
        private val actions = ArrayDeque<JSONObject>()
        private val listener = object : OverlayCoordinator.Listener {
            override fun hide() {
                mainHandler.post { root.visibility = View.INVISIBLE }
            }

            override fun show() {
                mainHandler.post { root.visibility = View.VISIBLE }
            }

            override fun awaitHidden(timeoutMs: Long) {
                OverlayCoordinator.awaitViewHidden(root, timeoutMs)
            }
        }
        private var shown = false

        fun show() {
            buildContent(options.optJSONArray("content"))
            windowManager.addView(root, params)
            // 先标记已显示：后续 register 抛错时 close() 仍能移除窗口。
            shown = true
            OverlayCoordinator.register(listener)
        }

        fun update(options: JSONObject) {
            if (options.has("x")) params.x = options.optInt("x", params.x)
            if (options.has("y")) params.y = options.optInt("y", params.y)
            if (options.has("width")) params.width = options.optInt("width", params.width)
            if (options.has("height")) params.height = options.optInt("height", params.height)
            if (options.has("touchable") ||
                options.has("focusable") ||
                options.has("coverStatusBar")
            ) {
                params.flags = flagsOf(options)
            }
            runCatching { windowManager.updateViewLayout(root, params) }
            options.optJSONArray("content")?.let {
                root.removeAllViews()
                views.clear()
                buildContent(it)
            }
            options.optJSONObject("views")?.let { updateViews(it) }
        }

        fun close() {
            if (!shown) return
            shown = false
            OverlayCoordinator.unregister(listener)
            runCatching { windowManager.removeView(root) }
        }

        fun drainActions(): JSONArray = synchronized(actions) {
            val array = JSONArray()
            while (actions.isNotEmpty()) array.put(actions.removeFirst())
            array
        }

        fun pollAction(): JSONObject? = synchronized(actions) {
            if (actions.isEmpty()) null else actions.removeFirst()
        }

        fun status(): JSONObject = JSONObject().apply {
            put("id", id)
            put("ok", true)
            put("x", params.x)
            put("y", params.y)
            put("width", params.width)
            put("height", params.height)
            put("views", JSONArray().apply {
                views.keys.forEach { put(it) }
            })
        }

        private fun updateViews(values: JSONObject) {
            values.keys().forEach { key ->
                val view = views[key] ?: return@forEach
                val value = values.opt(key)
                when (value) {
                    is Boolean -> when (view) {
                        // switch 的布尔值表示选中态，其余控件表示可见性。
                        is Switch -> setSwitchChecked(view, value, key)
                        else -> view.visibility = if (value) View.VISIBLE else View.GONE
                    }

                    is Number -> when (view) {
                        is ProgressBar -> view.progress = value.toInt()
                        is TextView -> view.textSize = value.toFloat()
                    }

                    is String -> when (view) {
                        is TextView -> view.text = value
                        is Switch -> view.text = value
                        else -> Unit
                    }
                }
            }
        }

        private fun buildContent(content: JSONArray?) {
            if (content == null) {
                addText("脚本悬浮窗", null)
                return
            }
            for (index in 0 until content.length()) {
                content.optJSONObject(index)?.let { spec ->
                    specView(spec)?.let { root.addView(it) }
                }
            }
        }

        private fun specView(spec: JSONObject): View? = when (spec.optString("type", "text")) {
            "text" -> addText(spec.optString("text"), spec)
            "button" -> addButton(spec)
            "switch" -> addSwitch(spec)
            "progress" -> addProgress(spec)
            "image" -> addImage(spec)
            "row", "column" -> addContainer(spec)
            "spacer" -> View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    spec.optInt("height", 8),
                )
            }

            else -> null
        }

        private fun addText(text: String?, spec: JSONObject?): View = TextView(context).apply {
            this.text = text.orEmpty()
            setTextColor(colorOf(spec?.optString("color"), Color.WHITE))
            textSize = spec?.optDouble("textSize", 14.0)?.toFloat() ?: 14f
            setPadding(4, 6, 4, 6)
            spec?.optString("id")?.takeIf { it.isNotBlank() }?.let { views[it] = this }
        }

        private fun addButton(spec: JSONObject): View = Button(context).apply {
            text = spec.optString("text", "按钮")
            setTextColor(colorOf(spec.optString("color"), Color.WHITE))
            textSize = spec.optDouble("textSize", 14.0).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(colorOf(spec.optString("background"), Color.parseColor("#FF3B82F6")))
            }
            setOnClickListener {
                enqueueAction(spec.optString("id"), "click", null)
            }
            spec.optString("id").takeIf { it.isNotBlank() }?.let { views[it] = this }
        }

        private fun addSwitch(spec: JSONObject): View {
            val view = Switch(context)
            view.text = spec.optString("text")
            view.setTextColor(colorOf(spec.optString("color"), Color.WHITE))
            setSwitchChecked(view, spec.optBoolean("checked", false), spec.optString("id"))
            spec.optString("id").takeIf { it.isNotBlank() }?.let { views[it] = view }
            return view
        }

        /**
         * 更新开关选中态。
         *
         * 先摘掉监听再设值：脚本用 `floaty.update` 同步开关状态时，不应在动作队列里
         * 留下一条自己造成的 change，否则 `waitAction` 会读到脚本自己的更新。
         */
        private fun setSwitchChecked(view: Switch, checked: Boolean, id: String) {
            view.setOnCheckedChangeListener(null)
            view.isChecked = checked
            view.setOnCheckedChangeListener { _, value -> enqueueAction(id, "change", value) }
        }

        private fun addProgress(spec: JSONObject): View = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = spec.optInt("max", 100).coerceAtLeast(1)
            progress = spec.optInt("value", 0).coerceIn(0, max)
            spec.optString("id").takeIf { it.isNotBlank() }?.let { views[it] = this }
        }

        private fun addImage(spec: JSONObject): View? {
            val path = spec.optString("path").trim()
            if (path.isEmpty()) return null
            val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
            return ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                spec.optString("id").takeIf { it.isNotBlank() }?.let { views[it] = this }
            }
        }

        private fun addContainer(spec: JSONObject): View = LinearLayout(context).apply {
            orientation = if (spec.optString("type") == "row") {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
            spec.optJSONArray("items")?.let { items ->
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)?.let { child ->
                        specView(child)?.let { addView(it) }
                    }
                }
            }
            spec.optString("id").takeIf { it.isNotBlank() }?.let { views[it] = this }
        }

        private fun enqueueAction(id: String, type: String, checked: Boolean?) {
            val action = JSONObject().apply {
                put("id", id)
                put("type", type)
                checked?.let { put("checked", it) }
            }
            synchronized(actions) {
                actions.addLast(action)
            }
        }

        private fun flagsOf(options: JSONObject): Int {
            var flags = 0
            if (!options.optBoolean("focusable", false)) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (!options.optBoolean("touchable", true)) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (options.optBoolean("coverStatusBar", false)) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            return flags
        }

        private fun colorOf(value: String?, fallback: Int): Int =
            runCatching { Color.parseColor(value) }.getOrDefault(fallback)
    }
}
