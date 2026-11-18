package com.benton.izukijs.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrModelsTest {

    @Test
    fun derivedCenterAndSizeMatchBoundingBox() {
        val block = OcrBlock(
            text = "确定",
            left = 10,
            top = 20,
            right = 30,
            bottom = 60,
        )

        assertEquals(20, block.x)
        assertEquals(40, block.y)
        assertEquals(20, block.width)
        assertEquals(40, block.height)
    }

    @Test
    fun invertedBoundingBoxNeverProducesNegativeSize() {
        val block = OcrBlock(
            text = "异常",
            left = 30,
            top = 60,
            right = 10,
            bottom = 20,
        )

        assertEquals(20, block.x)
        assertEquals(40, block.y)
        assertEquals(0, block.width)
        assertEquals(0, block.height)
    }

    @Test
    fun configDefaultsAreLocalBaiduWithoutSecrets() {
        val config = OcrConfig()

        assertEquals(OcrMode.LOCAL, config.mode)
        assertEquals(OcrProvider.BAIDU, config.provider)
        assertEquals("", config.apiKey)
        assertEquals("", config.secretKey)
    }
}
