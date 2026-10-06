package com.benton.izukijs.ocr

import android.graphics.Bitmap
import com.benton.izukijs.runtime.LogBus
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

    fun recognize(bitmap: Bitmap): OcrResult? {
        val config = configRepository.current()
        return when (config.mode) {
            OcrMode.LOCAL -> local.recognize(bitmap)

            OcrMode.ONLINE -> {
                val online = runCatching {
                    OnlineOcrEngine(config, captureSettingsRepository.current(), logBus).recognize(bitmap)
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
}
