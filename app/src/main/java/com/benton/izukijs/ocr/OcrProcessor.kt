package com.benton.izukijs.ocr

import android.graphics.Bitmap
import com.benton.izukijs.core.log.LogBus
import com.benton.izukijs.core.perf.PerfMetrics
import com.benton.izukijs.service.CaptureSettings
import com.benton.izukijs.service.CaptureSettingsRepository

/**
 * OCR 路由器：根据设备上保存的配置决定使用本地还是在线识别。
 * 在线失败时自动回退本地，保证脚本不中断。
 */
class OcrProcessor(
    private val configRepository: OcrConfigRepository,
    private val captureSettingsRepository: CaptureSettingsRepository,
    private val logBus: LogBus,
) {

    private val local = LocalOcrEngine()

    /** 在线引擎只依赖配置，配置不变时复用，避免每次识别都重新构造。 */
    private val engineLock = Any()
    private var onlineEngine: OnlineOcrEngine? = null
    private var onlineKey: Pair<OcrConfig, CaptureSettings>? = null

    /** 本地模式预热识别客户端；在线模式无需预热。 */
    fun prewarm() {
        if (configRepository.current().mode == OcrMode.LOCAL) {
            PerfMetrics.measure("ocr.prewarm") { local.prewarm() }
        }
    }

    fun recognize(bitmap: Bitmap): OcrResult? = PerfMetrics.measure("ocr.recognize") {
        val result = recognizeRaw(bitmap) ?: return@measure null
        val sanitized = OcrGeometry.sanitize(result.blocks, bitmap.width, bitmap.height)
        if (sanitized.clamped > 0 || sanitized.dropped > 0) {
            logBus.warn(
                "OCR 包围盒越界：裁剪 ${sanitized.clamped} 块、丢弃 ${sanitized.dropped} 块" +
                    "（图像 ${bitmap.width}×${bitmap.height}）",
            )
        }
        // 没有裁剪 / 丢弃时直接复用原结果，避免无谓的整份 blocks 拷贝。
        if (sanitized.clamped == 0 && sanitized.dropped == 0) result
        else result.copy(blocks = sanitized.blocks)
    }

    private fun recognizeRaw(bitmap: Bitmap): OcrResult? {
        val config = configRepository.current()
        return when (config.mode) {
            OcrMode.LOCAL -> local.recognize(bitmap)

            OcrMode.ONLINE -> {
                val capture = captureSettingsRepository.current()
                val online = runCatching {
                    engine(config, capture).recognize(bitmap)
                }.getOrNull()
                if (online != null) {
                    online
                } else {
                    logBus.warn("在线 OCR 未返回结果，回退本地识别")
                    local.recognize(bitmap)
                }
            }
        }
    }

    private fun engine(config: OcrConfig, capture: CaptureSettings): OnlineOcrEngine =
        synchronized(engineLock) {
            val key = config to capture
            onlineEngine?.takeIf { onlineKey == key }?.let { return it }
            OnlineOcrEngine(config, capture, logBus).also {
                onlineEngine = it
                onlineKey = key
            }
        }
}
