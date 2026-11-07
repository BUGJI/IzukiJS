package com.benton.izukijs.virtualscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设备分辨率档位。
 *
 * 读取主屏真实宽高与密度后，按长边上限等比缩小，并同步缩放 dpi，保持逻辑 dp 尺寸
 * 与真机一致。方向跟随读取时的设备方向；虚拟屏创建后仍由特权服务固定为 ROTATION_0。
 */
object DeviceScreenResolution {

    fun read(context: Context): VirtualScreenResolution {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return fit(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    /** 长边不超过 [maxLongSide]，宽高与 dpi 同步缩放；无效指标回退到 720P。 */
    fun fit(
        width: Int,
        height: Int,
        dpi: Int,
        maxLongSide: Int = VirtualScreenResolution.DEVICE_MAX_LONG_SIDE,
    ): VirtualScreenResolution {
        if (width <= 0 || height <= 0 || dpi <= 0) return VirtualScreenResolution.P720
        val longSide = max(width, height)
        val cap = maxLongSide.coerceAtLeast(1)
        val scale = if (longSide > cap) cap.toFloat() / longSide else 1f
        return VirtualScreenResolution(
            width = (width * scale).roundToInt(),
            height = (height * scale).roundToInt(),
            dpi = (dpi * scale).roundToInt(),
        ).normalized()
    }
}
