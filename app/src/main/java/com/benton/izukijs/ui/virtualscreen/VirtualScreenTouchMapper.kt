package com.benton.izukijs.ui.virtualscreen

/** 预览区域内实际画面的位置与缩放。 */
data class PreviewViewport(
    val contentLeft: Float,
    val contentTop: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val scale: Float,
)

/** 虚拟屏坐标（像素）。 */
data class VirtualScreenPoint(val x: Float, val y: Float)

/**
 * 把预览控件内的触摸坐标映射到虚拟屏像素坐标。
 *
 * 预览按等比缩放居中绘制，因此先去掉留白，再按 scale 反推。
 */
object VirtualScreenTouchMapper {

    fun viewport(
        viewWidth: Float,
        viewHeight: Float,
        screenWidth: Int,
        screenHeight: Int,
    ): PreviewViewport {
        if (viewWidth <= 0f || viewHeight <= 0f || screenWidth <= 0 || screenHeight <= 0) {
            return PreviewViewport(0f, 0f, 0f, 0f, 1f)
        }
        val scale = minOf(
            viewWidth / screenWidth.toFloat(),
            viewHeight / screenHeight.toFloat(),
        )
        val contentWidth = screenWidth * scale
        val contentHeight = screenHeight * scale
        return PreviewViewport(
            contentLeft = (viewWidth - contentWidth) / 2f,
            contentTop = (viewHeight - contentHeight) / 2f,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            scale = scale,
        )
    }

    fun map(
        x: Float,
        y: Float,
        viewport: PreviewViewport,
        screenWidth: Int,
        screenHeight: Int,
    ): VirtualScreenPoint? {
        if (viewport.scale <= 0f || screenWidth <= 0 || screenHeight <= 0) return null
        val maxX = screenWidth - 1f
        val maxY = screenHeight - 1f
        val mappedX = ((x - viewport.contentLeft) / viewport.scale).coerceIn(0f, maxX)
        val mappedY = ((y - viewport.contentTop) / viewport.scale).coerceIn(0f, maxY)
        return VirtualScreenPoint(mappedX, mappedY)
    }

    /** 触摸点是否落在实际画面内（用于忽略黑边上的手势）。 */
    fun inside(x: Float, y: Float, viewport: PreviewViewport): Boolean =
        x >= viewport.contentLeft &&
            y >= viewport.contentTop &&
            x <= viewport.contentLeft + viewport.contentWidth &&
            y <= viewport.contentTop + viewport.contentHeight
}
