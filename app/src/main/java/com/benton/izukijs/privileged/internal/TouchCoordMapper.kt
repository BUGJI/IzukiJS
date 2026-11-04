package com.benton.izukijs.privileged.internal

import com.benton.izukijs.service.TouchInputDevice

/** 输入设备原始坐标 -> 当前旋转下的逻辑屏幕坐标。 */
internal object TouchCoordMapper {

    fun mapStrokes(
        strokes: List<TouchStroke>,
        device: TouchInputDevice,
        screen: ScreenGeometry,
    ): List<TouchStroke> = strokes.map { stroke ->
        TouchStroke(
            points = stroke.points.map { point ->
                val (x, y) = mapPoint(point.x, point.y, device, screen)
                TouchPoint(x, y, point.tMs)
            },
            endTMs = stroke.endTMs,
        )
    }

    fun mapPoint(
        rawX: Int,
        rawY: Int,
        device: TouchInputDevice,
        screen: ScreenGeometry,
    ): Pair<Int, Int> {
        // 面板坐标轴固定在自然方向，先还原自然尺寸。
        val naturalWidth = if (screen.rotation % 2 == 0) screen.width else screen.height
        val naturalHeight = if (screen.rotation % 2 == 0) screen.height else screen.width

        val nx = ((rawX - device.minX).toLong() * naturalWidth / device.rangeX)
            .toInt().coerceIn(0, naturalWidth - 1)
        val ny = ((rawY - device.minY).toLong() * naturalHeight / device.rangeY)
            .toInt().coerceIn(0, naturalHeight - 1)

        return naturalToLogical(nx, ny, naturalWidth, naturalHeight, screen.rotation)
    }

    fun naturalToLogical(
        nx: Int,
        ny: Int,
        naturalWidth: Int,
        naturalHeight: Int,
        rotation: Int,
    ): Pair<Int, Int> = when (rotation) {
        1 -> ny to (naturalWidth - 1 - nx)
        2 -> (naturalWidth - 1 - nx) to (naturalHeight - 1 - ny)
        3 -> (naturalHeight - 1 - ny) to nx
        else -> nx to ny
    }
}
