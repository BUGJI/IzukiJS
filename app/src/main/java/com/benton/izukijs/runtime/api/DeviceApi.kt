package com.benton.izukijs.runtime.api

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.webkit.JavascriptInterface
import com.benton.izukijs.virtualscreen.ScreenTarget
import com.benton.izukijs.virtualscreen.VirtualScreenManager
import com.benton.izukijs.virtualscreen.VirtualScreenResolution

/**
 * 设备信息 API。挂在全局 `device` 命名空间。
 *
 * 虚拟屏激活且当前目标为虚拟屏时，宽高返回虚拟屏分辨率，保证脚本里按
 * `device.width()/height()` 计算的坐标与截图、OCR、点击使用同一坐标系。
 * 其它情况下 [width] / [height] 返回应用窗口度量，[screenWidth] / [screenHeight]
 * 返回物理屏幕的真实分辨率。
 */
class DeviceApi(
    private val context: Context,
    private val virtualScreen: VirtualScreenManager? = null,
) {

    @JavascriptInterface
    fun width(): Int = virtualResolution()?.width ?: context.resources.displayMetrics.widthPixels

    @JavascriptInterface
    fun height(): Int = virtualResolution()?.height ?: context.resources.displayMetrics.heightPixels

    @JavascriptInterface
    fun screenWidth(): Int = virtualResolution()?.width ?: realMetrics().widthPixels

    @JavascriptInterface
    fun screenHeight(): Int = virtualResolution()?.height ?: realMetrics().heightPixels

    /** 主屏真实宽高；不受虚拟屏目标影响，供 target=main 的归一化坐标换算。 */
    fun mainScreenWidth(): Int = realMetrics().widthPixels

    fun mainScreenHeight(): Int = realMetrics().heightPixels

    /**
     * 当前屏幕旋转：0 = 0°，1 = 90°，2 = 180°，3 = 270°。
     *
     * 脚本在运行中可能遇到横竖屏切换，可据此重新计算坐标或跳过当前轮次。
     */
    @JavascriptInterface
    fun rotation(): Int = runCatching {
        context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: 0
    }.getOrDefault(0)

    private fun virtualResolution(): VirtualScreenResolution? =
        virtualScreen
            ?.takeIf { it.isActive() && it.resolvedTarget() == ScreenTarget.VIRTUAL }
            ?.size()

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(context.resources.displayMetrics)
        }
        return metrics
    }

    @JavascriptInterface
    fun model(): String = Build.MODEL ?: "unknown"

    @JavascriptInterface
    fun brand(): String = Build.BRAND ?: "unknown"

    @JavascriptInterface
    fun androidVersion(): Int = Build.VERSION.SDK_INT

    @JavascriptInterface
    fun batteryLevel(): Int {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    // ---- 音量：只操作媒体 / 通知 / 闹钟流，失败返回 -1 / false ----

    @JavascriptInterface
    fun getMusicVolume(): Int = streamVolume(AudioManager.STREAM_MUSIC)

    @JavascriptInterface
    fun setMusicVolume(volume: Int): Boolean = setStreamVolume(AudioManager.STREAM_MUSIC, volume)

    @JavascriptInterface
    fun getMusicMaxVolume(): Int = streamMaxVolume(AudioManager.STREAM_MUSIC)

    @JavascriptInterface
    fun getNotificationVolume(): Int = streamVolume(AudioManager.STREAM_NOTIFICATION)

    @JavascriptInterface
    fun setNotificationVolume(volume: Int): Boolean =
        setStreamVolume(AudioManager.STREAM_NOTIFICATION, volume)

    @JavascriptInterface
    fun getNotificationMaxVolume(): Int = streamMaxVolume(AudioManager.STREAM_NOTIFICATION)

    @JavascriptInterface
    fun getAlarmVolume(): Int = streamVolume(AudioManager.STREAM_ALARM)

    @JavascriptInterface
    fun setAlarmVolume(volume: Int): Boolean = setStreamVolume(AudioManager.STREAM_ALARM, volume)

    @JavascriptInterface
    fun getAlarmMaxVolume(): Int = streamMaxVolume(AudioManager.STREAM_ALARM)

    private fun audio(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private fun streamVolume(stream: Int): Int =
        runCatching { audio()?.getStreamVolume(stream) ?: -1 }.getOrDefault(-1)

    private fun streamMaxVolume(stream: Int): Int =
        runCatching { audio()?.getStreamMaxVolume(stream) ?: -1 }.getOrDefault(-1)

    private fun setStreamVolume(stream: Int, volume: Int): Boolean = runCatching {
        val manager = audio() ?: return false
        val maximum = manager.getStreamMaxVolume(stream)
        if (maximum <= 0) return false
        manager.setStreamVolume(stream, volume.coerceIn(0, maximum), 0)
        true
    }.getOrDefault(false)

    // ---- 亮度：读取不需要权限，写入需要 WRITE_SETTINGS ----

    @JavascriptInterface
    fun getBrightness(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(-1)

    @JavascriptInterface
    fun setBrightness(brightness: Int): Boolean = runCatching {
        if (!Settings.System.canWrite(context)) return false
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightness.coerceIn(0, 255),
        )
    }.getOrDefault(false)

    /** 0 = 手动亮度，1 = 自动亮度。 */
    @JavascriptInterface
    fun getBrightnessMode(): Int = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
        )
    }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)

    @JavascriptInterface
    fun setBrightnessMode(mode: Int): Boolean = runCatching {
        if (!Settings.System.canWrite(context)) return false
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            },
        )
    }.getOrDefault(false)

    // ---- 振动：需要 VIBRATE 权限；无振动马达返回 false ----

    @JavascriptInterface
    fun vibrate(milliseconds: Double): Boolean {
        val vibrator = vibrator() ?: return false
        if (!vibrator.hasVibrator()) return false
        val duration = milliseconds.toLong().coerceIn(1L, MAX_VIBRATION_MS)
        return runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE),
            )
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun cancelVibration(): Boolean {
        val vibrator = vibrator() ?: return false
        runCatching { vibrator.cancel() }
        return true
    }

    private fun vibrator(): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private companion object {
        const val MAX_VIBRATION_MS = 60_000L
    }
}
