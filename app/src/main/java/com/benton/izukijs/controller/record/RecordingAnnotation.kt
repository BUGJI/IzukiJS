package com.benton.izukijs.controller.record

/** 录制帧标注的横 / 纵缩放。 */
data class AnnotationScale(val x: Float, val y: Float)

/**
 * 计算把录制动作坐标画到帧上时使用的缩放。
 *
 * - 帧与动作方向不一致（横屏帧配竖屏坐标）时返回 null：坐标属于另一套画布，
 *   继续画只会漂到画面外。
 * - 动作没有记录屏幕尺寸（脚本动作）时按 1:1 处理。
 * - 横竖屏切换后宽高比会变，必须两轴分别缩放，不能只按宽度缩放。
 */
fun annotationScale(
    frameWidth: Int,
    frameHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): AnnotationScale? {
    if (frameWidth <= 0 || frameHeight <= 0) return null
    if (screenWidth <= 0 || screenHeight <= 0) return AnnotationScale(1f, 1f)
    if ((frameWidth > frameHeight) != (screenWidth > screenHeight)) return null
    return AnnotationScale(
        x = frameWidth.toFloat() / screenWidth,
        y = frameHeight.toFloat() / screenHeight,
    )
}
