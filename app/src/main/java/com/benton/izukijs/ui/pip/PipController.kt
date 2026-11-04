package com.benton.izukijs.ui.pip

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.compositionLocalOf
import com.benton.izukijs.virtualscreen.VirtualScreenResolution

/** PiP 只存参数，不持有 Activity 引用。 */
data class PipRequest(
    val resolution: VirtualScreenResolution,
)

/** 运行页通过它把 PiP 武装请求交给 MainActivity。 */
interface PipHost {
    var pipRequest: PipRequest?
}

val LocalIsInPip = compositionLocalOf { false }

/** 纯 JVM 单测里 Rational 是抛异常的 stub，先用自己的比值类型做 clamp。 */
data class PipAspectRatio(val numerator: Int, val denominator: Int)

/**
 * 系统画中画入口。后台虚拟屏运行中、停在运行页并按 Home 时进入 PiP，
 * 只显示虚拟屏预览，窗口内触摸由系统消费为“展开应用”。
 */
object PipController {

    private const val MAX_RATIO_NUM = 239
    private const val MAX_RATIO_DEN = 100
    internal const val MAX_RATIO = MAX_RATIO_NUM.toFloat() / MAX_RATIO_DEN
    internal const val MIN_RATIO = 1f / MAX_RATIO

    internal val FALLBACK_RATIO = PipAspectRatio(16, 9)

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun clampAspectRatio(width: Int, height: Int): PipAspectRatio {
        if (width <= 0 || height <= 0) return FALLBACK_RATIO
        val ratio = width.toFloat() / height
        return when {
            ratio > MAX_RATIO -> PipAspectRatio(MAX_RATIO_NUM, MAX_RATIO_DEN)
            ratio < MIN_RATIO -> PipAspectRatio(MAX_RATIO_DEN, MAX_RATIO_NUM)
            else -> PipAspectRatio(width, height)
        }
    }

    /** API 31+ 顺带开合 auto-enter；低版本只更新参数，真正进入靠 [enterNow]。 */
    fun updateParams(activity: Activity, autoEnter: Boolean, request: PipRequest) {
        if (!isSupported(activity)) return
        runCatching {
            activity.setPictureInPictureParams(buildParams(request, autoEnter))
        }
    }

    /** API 28~30 没有 auto-enter，只能在 onUserLeaveHint 里手动进入。 */
    fun enterNow(activity: Activity, request: PipRequest): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        if (!isSupported(activity)) return false
        if (activity.isInPictureInPictureMode) return false
        return runCatching {
            activity.enterPictureInPictureMode(buildParams(request, autoEnter = false))
        }.getOrDefault(false)
    }

    private fun buildParams(request: PipRequest, autoEnter: Boolean): PictureInPictureParams {
        val ratio = clampAspectRatio(request.resolution.width, request.resolution.height)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(ratio.numerator, ratio.denominator))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }
}
