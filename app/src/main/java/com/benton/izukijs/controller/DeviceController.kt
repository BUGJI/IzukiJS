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
)

/**
 * 控制抽象层的核心接口。三种（Root 为四种）模式各自实现该接口，
 * 上层脚本 API 通过 [ControllerManager] 按能力选取具体实现，从而感知不到底层差异。
 */
interface DeviceController {
    val mode: ControlMode

    /** 该模式当前可提供的能力集合。 */
    fun capabilities(): Set<Capability>

    /** 该模式当前是否已就绪（例如无障碍服务已连接、HID 已连接）。 */
    fun isReady(): Boolean

    fun click(x: Float, y: Float): Boolean = false

    fun longClick(x: Float, y: Float, durationMs: Long = 600L): Boolean = false

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 300L,
    ): Boolean = false

    /**
     * 是否支持带时间戳的复杂轨迹（曲线 / 多段 / 多指 / 按住停顿）。
     * 目前仅无障碍后端支持；其余后端 [gesture] 恒为 false。
     */
    fun supportsGesture(): Boolean = false

    fun gesture(strokes: List<GestureStroke>): Boolean = false

    fun pressKey(keyCode: Int): Boolean = false

    fun inputText(text: String): Boolean = false

    /** 执行无障碍全局动作（返回/主页/最近任务等）。 */
    fun globalAction(action: Int): Boolean = false

    fun screenshot(): Bitmap? = null

    fun nodeTree(): NodeSnapshot? = null

    fun shell(cmd: String): ShellResult? = null
}
