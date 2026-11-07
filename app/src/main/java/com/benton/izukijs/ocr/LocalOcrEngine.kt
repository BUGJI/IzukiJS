package com.benton.izukijs.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地 OCR（MLKit 中文模型，端侧，无需网络）。
 */
class LocalOcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 触发客户端初始化，减少首次识别时的冷启动开销。 */
    fun prewarm() {
        runCatching { recognizer }
    }

    fun recognize(bitmap: Bitmap): OcrResult? {
        val visionText = recognizeBlocking(bitmap) ?: return null
        val blocks = ArrayList<OcrBlock>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rect = element.boundingBox
                    blocks.add(
                        OcrBlock(
                            text = element.text,
                            left = rect?.left ?: 0,
                            top = rect?.top ?: 0,
                            right = rect?.right ?: 0,
                            bottom = rect?.bottom ?: 0,
                            confidence = element.confidence?.takeIf { it >= 0f }?.toDouble(),
                        ),
                    )
                }
            }
        }
        return OcrResult(visionText.text, blocks)
    }

    private fun recognizeBlocking(bitmap: Bitmap): Text? {
        val latch = CountDownLatch(1)
        var output: Text? = null
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener {
                output = it
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
        return try {
            if (latch.await(15, TimeUnit.SECONDS)) output else null
        } catch (t: Throwable) {
            null
        }
    }
}
