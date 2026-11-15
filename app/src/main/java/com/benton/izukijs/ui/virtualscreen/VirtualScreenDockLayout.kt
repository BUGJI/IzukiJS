package com.benton.izukijs.ui.virtualscreen

/**
 * 预览画面与右侧快捷操作栏的尺寸计算。
 *
 * 全部使用 dp 浮点值，不依赖 android.*，便于 JVM 单测。
 * dock 只在预览足够高时保持单列；横屏或宽高比较小的预览会自动折成两列，
 * 避免操作栏比预览还高。
 */
object VirtualScreenDockLayout {

    const val MIN_PREVIEW_RATIO = 0.5f
    const val MAX_PREVIEW_RATIO = 2.5f

    const val BUTTON_SIZE_DP = 48f
    const val BUTTON_GAP_DP = 4f
    const val DOCK_GAP_DP = 12f

    const val PHONE_MAX_PREVIEW_HEIGHT_DP = 360f
    const val WIDE_MAX_PREVIEW_HEIGHT_DP = 480f

    private const val ACTION_COUNT = 5
    private const val SINGLE_COLUMN_ROWS = ACTION_COUNT
    private const val TWO_COLUMN_ROWS = (ACTION_COUNT + 1) / 2
    private const val MIN_DOCK_PREVIEW_WIDTH_DP = 96f

    private const val SINGLE_COLUMN_HEIGHT_DP =
        SINGLE_COLUMN_ROWS * BUTTON_SIZE_DP + (SINGLE_COLUMN_ROWS - 1) * BUTTON_GAP_DP
    private const val SINGLE_COLUMN_WIDTH_DP = BUTTON_SIZE_DP
    private const val TWO_COLUMN_WIDTH_DP = 2 * BUTTON_SIZE_DP + BUTTON_GAP_DP

    /** 计算结果；[dockColumns] 为 0 表示宽度不足，不渲染 dock。 */
    data class Result(
        val previewWidth: Float,
        val previewHeight: Float,
        val dockColumns: Int,
        val dockWidth: Float,
    ) {
        val showDock: Boolean get() = dockColumns > 0
    }

    fun compute(
        availableWidth: Float,
        aspectRatio: Float,
        maxPreviewHeight: Float,
    ): Result {
        if (availableWidth <= 0f) return Result(0f, 0f, 0, 0f)
        val ratio = aspectRatio.coerceIn(MIN_PREVIEW_RATIO, MAX_PREVIEW_RATIO)
        val maxHeight = maxPreviewHeight.coerceAtLeast(1f)

        val singlePreviewWidth = availableWidth - DOCK_GAP_DP - SINGLE_COLUMN_WIDTH_DP
        val singlePreviewHeight = fitHeight(singlePreviewWidth, ratio, maxHeight)
        val single = singlePreviewHeight >= SINGLE_COLUMN_HEIGHT_DP
        val dockColumns = if (single) 1 else 2
        val dockWidth = if (single) SINGLE_COLUMN_WIDTH_DP else TWO_COLUMN_WIDTH_DP

        val previewWidth = availableWidth - DOCK_GAP_DP - dockWidth
        if (previewWidth < MIN_DOCK_PREVIEW_WIDTH_DP) {
            val fullWidth = fitWidth(availableWidth, ratio, maxHeight)
            val fullHeight = fitHeight(availableWidth, ratio, maxHeight)
            return Result(fullWidth, fullHeight, 0, 0f)
        }

        return Result(
            previewWidth = fitWidth(previewWidth, ratio, maxHeight),
            previewHeight = fitHeight(previewWidth, ratio, maxHeight),
            dockColumns = dockColumns,
            dockWidth = dockWidth,
        )
    }

    /** 两列 dock 的高度，供布局决定是否需要在预览下方补足空间。 */
    fun dockHeight(dockColumns: Int): Float = when (dockColumns) {
        1 -> SINGLE_COLUMN_HEIGHT_DP
        2 -> TWO_COLUMN_ROWS * BUTTON_SIZE_DP + (TWO_COLUMN_ROWS - 1) * BUTTON_GAP_DP
        else -> 0f
    }

    private fun fitHeight(width: Float, ratio: Float, maxHeight: Float): Float {
        if (width <= 0f) return 0f
        return minOf(width / ratio, maxHeight)
    }

    private fun fitWidth(width: Float, ratio: Float, maxHeight: Float): Float =
        fitHeight(width, ratio, maxHeight) * ratio
}
