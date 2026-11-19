package com.benton.izukijs.controller

import android.graphics.Bitmap
import com.benton.izukijs.model.Capability
import com.benton.izukijs.model.ControlMode

/** 手势轨迹中的一个点。 */
data class GesturePoint(val x: Float, val y: Float, val timeMs: Long)

/** 一条完整手势轨迹（一条笔画的连续点）。 */
data class GestureStroke(val points: List<GesturePoint>)

/** Shell 执行结果。 */
data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int)

/** 文本输入方式。 */
enum class TextInputMethod {
    /** 自动：ASCII 用 `input text`，非 ASCII 优先剪贴板粘贴，其次广播输入法。 */
    AUTO,

    /** 仅 `input text`（只支持 ASCII）。 */
    INPUT,

    /** 先写入系统剪贴板，再发送粘贴键。 */
    CLIPBOARD,

    /** 广播输入法（ADBKeyboard 的 `ADB_INPUT_*`）。 */
    BROADCAST,
    ;

    companion object {
        fun from(id: String?): TextInputMethod =
            entries.firstOrNull { it.name.equals(id?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * 控件树读取结果。区分「成功」「无可用后端」「后端存在但读取失败」三种语义，
 * 避免把 Shizuku 已连接但 `uiautomator dump` 失败的情况误报为「需开启无障碍或 Shizuku」。
 */
sealed interface NodeTreeResult {
    /** 成功读到控件树。 */
    data class Success(val root: NodeSnapshot) : NodeTreeResult

    /** 没有任何可用后端（无障碍未开启，且无 Shizuku / 无线调试 / Root）。 */
    data object NoBackend : NodeTreeResult

    /**
     * 后端存在但读取失败，[message] 为面向用户的错误提示。
     *
     * [timedOut] 标记「本次 dump 在 [budgetMs] 内未返回」，供上层决定是否放大预算重试；
     * 普通失败（解析失败、文件为空等）不应重试。
     */
    data class Failure(
        val message: String,
        val timedOut: Boolean = false,
        val budgetMs: Long = 0L,
    ) : NodeTreeResult
}

/** 控件树节点快照。 */
data class NodeSnapshot(
    val className: String?,
    val text: String?,
    val viewId: String?,
    val contentDescription: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val children: List<NodeSnapshot>,
    val packageName: String? = null,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val indexInParent: Int = 0,
    val depth: Int = 0,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
)

/** 屏幕坐标矩形，供选择器条件与快照匹配复用。 */
data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(other: NodeBounds): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    fun inside(other: NodeBounds): Boolean =
        left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom
}

/** 坐标点击与滑动能力。 */
interface PointerController {
    fun click(x: Float, y: Float): Boolean = false

    fun longClick(x: Float, y: Float, durationMs: Long = 600L): Boolean = false

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean = false
}

/**
 * 带时间戳的复杂轨迹（曲线 / 多段 / 多指 / 按住停顿）。
 * 目前仅无障碍后端支持；其余后端 [gesture] 恒为 false。
 */
interface GestureController {
    fun supportsGesture(): Boolean = false

    fun gesture(strokes: List<GestureStroke>): Boolean = false
}

/** 按键输入。 */
interface KeyController {
    fun pressKey(keyCode: Int): Boolean = false
}

/** 文本输入与无障碍全局动作。 */
interface TextController {
    fun inputText(text: String): Boolean = false

    /** 按指定方式输入文本；默认忽略 [method]，等价于 [inputText]。 */
    fun inputText(text: String, method: TextInputMethod): Boolean = inputText(text)

    /**
     * 当前是否有可编辑控件持有输入焦点。
     *
     * null 表示该后端无法判断（例如 Shell / HID 读不到控件树），调用方不得据此判定失败。
     */
    fun hasEditableFocus(): Boolean? = null

    /** 执行无障碍全局动作（返回/主页/最近任务等）。 */
    fun globalAction(action: Int): Boolean = false

    /**
     * 最近一次文本输入是否走剪贴板、以及原剪贴板是否成功恢复。
     * null 表示未走剪贴板或不支持该后端。
     */
    fun lastClipboardRestored(): Boolean? = null
}

/** 截图能力。 */
interface ScreenshotController {
    fun screenshot(): Bitmap? = null

    /** 截取指定 display；null 表示主屏。默认实现忽略参数。 */
    fun screenshotForDisplay(displayId: Int?): Bitmap? = screenshot()
}

/** 控件树读取。 */
interface NodeTreeController {
    fun nodeTree(): NodeSnapshot? = null
}

/** Shell 命令执行。 */
interface ShellController {
    fun shell(cmd: String): ShellResult? = null
}

/**
 * 控制抽象层的核心接口。三种（Root 为四种）模式各自实现该接口，
 * 上层脚本 API 通过 [ControllerManager] 按能力选取具体实现，从而感知不到底层差异。
 *
 * 具体操作按能力拆到 [PointerController] / [GestureController] / [KeyController] /
 * [TextController] / [ScreenshotController] / [NodeTreeController] / [ShellController]，
 * 新代码可只依赖需要的能力接口；现有调用点仍可直接使用本接口。
 */
interface DeviceController :
    PointerController,
    GestureController,
    KeyController,
    TextController,
    ScreenshotController,
    NodeTreeController,
    ShellController {

    val mode: ControlMode

    /** 该模式当前可提供的能力集合。 */
    fun capabilities(): Set<Capability>

    /** 该模式当前是否已就绪（例如无障碍服务已连接、HID 已连接）。 */
    fun isReady(): Boolean

    /**
     * 去掉录制代理等包装，返回真正执行的后端；非包装后端返回自身。
     *
     * 选择器 / 调试布局这类只读查询必须拿到真实无障碍实例：录制代理会记录并转发
     * 输入，但不是 [com.benton.izukijs.controller.accessibility.AccessibilityController]。
     */
    fun underlying(): DeviceController = this

    /**
     * 是否支持把输入 / 截图定向到指定 display。
     * 目前只有 Shizuku / 无线调试 / Root 这类 Shell 后端支持；无障碍与 HID 固定操作主屏。
     */
    fun supportsDisplayTarget(): Boolean = false

    /** 显式指定 display 的点击；默认后端不支持时返回 false。 */
    fun clickForDisplay(x: Float, y: Float, displayId: Int): Boolean = false

    /** 显式指定 display 的长按；默认后端不支持时返回 false。 */
    fun longClickForDisplay(x: Float, y: Float, durationMs: Long, displayId: Int): Boolean = false

    /** 显式指定 display 的滑动；默认后端不支持时返回 false。 */
    fun swipeForDisplay(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        displayId: Int,
    ): Boolean = false
}
