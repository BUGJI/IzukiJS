package com.benton.izukijs.ui.virtualscreen

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualScreenDockLayoutTest {

    private val phoneWidth = 328f
    private val phoneMaxHeight = VirtualScreenDockLayout.PHONE_MAX_PREVIEW_HEIGHT_DP

    @Test
    fun tallPortraitPreviewUsesSingleColumnDock() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = phoneWidth,
            aspectRatio = 0.5f,
            maxPreviewHeight = phoneMaxHeight,
        )

        assertEquals(1, result.dockColumns)
        assertEquals(VirtualScreenDockLayout.BUTTON_SIZE_DP, result.dockWidth, 0.01f)
        assertEquals(phoneMaxHeight, result.previewHeight, 0.01f)
        assertEquals(phoneMaxHeight * 0.5f, result.previewWidth, 0.01f)
        assertEquals(
            5 * VirtualScreenDockLayout.BUTTON_SIZE_DP + 4 * VirtualScreenDockLayout.BUTTON_GAP_DP,
            VirtualScreenDockLayout.dockHeight(1),
            0.01f,
        )
    }

    @Test
    fun squarePreviewBelowSingleColumnHeightUsesTwoColumns() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = 300f,
            aspectRatio = 1f,
            maxPreviewHeight = 300f,
        )

        assertEquals(2, result.dockColumns)
        assertEquals(188f, result.previewWidth, 0.01f)
        assertEquals(188f, result.previewHeight, 0.01f)
    }

    @Test
    fun squarePreviewAtSingleColumnHeightStaysSingleColumn() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = 320f,
            aspectRatio = 1f,
            maxPreviewHeight = 300f,
        )

        assertEquals(1, result.dockColumns)
        assertEquals(260f, result.previewWidth, 0.01f)
        assertEquals(260f, result.previewHeight, 0.01f)
    }

    @Test
    fun landscapePreviewFallsBackToTwoColumns() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = phoneWidth,
            aspectRatio = 16f / 9f,
            maxPreviewHeight = phoneMaxHeight,
        )

        assertEquals(2, result.dockColumns)
        assertEquals(
            2 * VirtualScreenDockLayout.BUTTON_SIZE_DP + VirtualScreenDockLayout.BUTTON_GAP_DP,
            result.dockWidth,
            0.01f,
        )
        assertEquals(
            VirtualScreenDockLayout.dockHeight(2),
            3 * VirtualScreenDockLayout.BUTTON_SIZE_DP + 2 * VirtualScreenDockLayout.BUTTON_GAP_DP,
            0.01f,
        )
        assertEquals(16f / 9f * result.previewHeight, result.previewWidth, 0.01f)
        assertEquals(
            phoneWidth - VirtualScreenDockLayout.DOCK_GAP_DP - result.dockWidth,
            result.previewWidth,
            0.01f,
        )
    }

    @Test
    fun narrowCardHidesDock() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = 100f,
            aspectRatio = 1f,
            maxPreviewHeight = phoneMaxHeight,
        )

        assertEquals(0, result.dockColumns)
        assertEquals(0f, result.dockWidth, 0.01f)
        assertEquals(100f, result.previewWidth, 0.01f)
        assertEquals(100f, result.previewHeight, 0.01f)
    }

    @Test
    fun widePreviewRaisesHeightCap() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = 470f,
            aspectRatio = 0.5f,
            maxPreviewHeight = VirtualScreenDockLayout.WIDE_MAX_PREVIEW_HEIGHT_DP,
        )

        assertEquals(1, result.dockColumns)
        assertEquals(
            VirtualScreenDockLayout.WIDE_MAX_PREVIEW_HEIGHT_DP,
            result.previewHeight,
            0.01f,
        )
        assertEquals(
            VirtualScreenDockLayout.WIDE_MAX_PREVIEW_HEIGHT_DP * 0.5f,
            result.previewWidth,
            0.01f,
        )
    }

    @Test
    fun aspectRatioIsClampedToSupportedRange() {
        val result = VirtualScreenDockLayout.compute(
            availableWidth = phoneWidth,
            aspectRatio = 0.1f,
            maxPreviewHeight = phoneMaxHeight,
        )

        assertEquals(
            phoneMaxHeight * VirtualScreenDockLayout.MIN_PREVIEW_RATIO,
            result.previewWidth,
            0.01f,
        )
    }
}
