package com.benton.izukijs.virtualscreen

import android.content.Context
import android.content.SharedPreferences
import com.benton.izukijs.core.prefs.PrefsStateStore
import com.benton.izukijs.model.RunMode
import kotlinx.coroutines.flow.StateFlow

/** 后台虚拟屏的分辨率与密度。宽高直接决定采集帧、点击坐标与 PiP 宽高比。 */
data class VirtualScreenResolution(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val dpi: Int = DEFAULT_DPI,
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height else DEFAULT_ASPECT_RATIO

    val isValid: Boolean
        get() = width in MIN_SIZE..MAX_SIZE && height in MIN_SIZE..MAX_SIZE && dpi in MIN_DPI..MAX_DPI

    fun normalized(): VirtualScreenResolution = copy(
        width = width.coerceIn(MIN_SIZE, MAX_SIZE),
        height = height.coerceIn(MIN_SIZE, MAX_SIZE),
        dpi = dpi.coerceIn(MIN_DPI, MAX_DPI),
    )

    companion object {
        const val MIN_SIZE = 320
        const val MAX_SIZE = 3840
        const val MIN_DPI = 120
        const val MAX_DPI = 640

        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_DPI = 240
        const val DEFAULT_ASPECT_RATIO = 16f / 9f

        /** 设备档位的长边上限，控制 ImageReader 内存与截图开销。 */
        const val DEVICE_MAX_LONG_SIDE = 1920

        val P720 = VirtualScreenResolution(1280, 720, 240)
        val P1080 = VirtualScreenResolution(1920, 1080, 280)

        /** 内置档位；自定义分辨率不在列表里。 */
        val presets: List<VirtualScreenResolution> = listOf(P720, P1080)

        fun fromJson(width: Int, height: Int, dpi: Int): VirtualScreenResolution =
            VirtualScreenResolution(width, height, dpi).normalized()
    }
}

/**
 * 虚拟屏偏好。
 *
 * [allowedModes] 是用户允许脚本使用的运行模式集合；前台始终允许。脚本通过
 * `@env RUN_MODE` 或 API 请求后台时，只有集合里包含 [RunMode.BACKGROUND] 且
 * Shizuku 特权服务可用才会生效。
 */
data class VirtualScreenSettings(
    val allowedModes: Set<RunMode> = setOf(RunMode.FOREGROUND),
    val defaultMode: RunMode = RunMode.FOREGROUND,
    val resolution: VirtualScreenResolution = VirtualScreenResolution.P720,
    /** 后台运行中从运行页按 Home 时自动进入系统画中画。 */
    val pipOnHome: Boolean = true,
    /** 脚本结束时自动销毁本次脚本启动的虚拟屏；关闭后保留到手动或脚本销毁。 */
    val autoStopAfterRun: Boolean = true,
    /**
     * 虚拟屏是否尝试显示系统装饰（状态栏 / 导航栏 insets）。
     *
     * 依赖隐藏 flag VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS，部分 ROM 会忽略；
     * 默认关闭，便于与旧行为对比。
     */
    val showSystemDecorations: Boolean = false,
) {
    val backgroundAllowed: Boolean get() = RunMode.BACKGROUND in allowedModes

    /** 默认模式不能超出允许集合；旧数据或损坏数据在这里兜底。 */
    fun normalized(): VirtualScreenSettings {
        val modes = allowedModes + RunMode.FOREGROUND
        val default = if (defaultMode in modes) defaultMode else RunMode.FOREGROUND
        return copy(
            allowedModes = modes,
            defaultMode = default,
            resolution = resolution.normalized(),
        )
    }
}

/** 虚拟屏上次运行状态；用于重启后给出停止原因。 */
data class VirtualScreenRuntimeState(
    val wasActive: Boolean = false,
    val lastStopReason: String? = null,
    val lastStopAt: Long = 0L,
)

/** SharedPreferences 持久化 + 内存缓存。 */
class VirtualScreenSettingsRepository private constructor(
    context: Context,
    private val deviceResolution: VirtualScreenResolution,
) : PrefsStateStore<VirtualScreenSettings>(
    context = context,
    prefsName = PREFS,
    defaultValue = VirtualScreenSettings(resolution = deviceResolution),
) {

    constructor(context: Context) : this(
        context = context,
        deviceResolution = DeviceScreenResolution.read(context),
    )

    val settings: StateFlow<VirtualScreenSettings> get() = state

    /** 全新安装 / 恢复默认时使用的配置。 */
    fun defaultSettings(): VirtualScreenSettings =
        VirtualScreenSettings(resolution = deviceResolution)

    fun runtimeState(): VirtualScreenRuntimeState = VirtualScreenRuntimeState(
        wasActive = prefs.getBoolean(KEY_WAS_ACTIVE, false),
        lastStopReason = prefs.getString(KEY_LAST_STOP_REASON, null),
        lastStopAt = prefs.getLong(KEY_LAST_STOP_AT, 0L),
    )

    fun markActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_WAS_ACTIVE, active).apply()
    }

    fun saveResolution(resolution: VirtualScreenResolution) {
        save(current().copy(resolution = resolution.normalized()))
    }

    fun recordStop(reason: String, atMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(KEY_WAS_ACTIVE, false)
            .putString(KEY_LAST_STOP_REASON, reason)
            .putLong(KEY_LAST_STOP_AT, atMillis)
            .apply()
    }

    override fun normalize(value: VirtualScreenSettings): VirtualScreenSettings = value.normalized()

    override fun read(): VirtualScreenSettings {
        val modes = prefs.getStringSet(KEY_ALLOWED_MODES, setOf(RunMode.FOREGROUND.name))
            .orEmpty()
            .mapNotNull { RunMode.from(it) }
            .toSet() + RunMode.FOREGROUND
        val default = RunMode.from(prefs.getString(KEY_DEFAULT_MODE, null)) ?: RunMode.FOREGROUND
        val resolution = VirtualScreenResolution(
            width = prefs.getInt(KEY_WIDTH, deviceResolution.width),
            height = prefs.getInt(KEY_HEIGHT, deviceResolution.height),
            dpi = prefs.getInt(KEY_DPI, deviceResolution.dpi),
        )
        return VirtualScreenSettings(
            allowedModes = modes,
            defaultMode = default,
            resolution = resolution,
            pipOnHome = prefs.getBoolean(KEY_PIP_ON_HOME, true),
            autoStopAfterRun = prefs.getBoolean(KEY_AUTO_STOP_AFTER_RUN, true),
            showSystemDecorations = prefs.getBoolean(KEY_SHOW_SYSTEM_DECORATIONS, false),
        ).normalized()
    }

    override fun write(editor: SharedPreferences.Editor, value: VirtualScreenSettings) {
        editor
            .putStringSet(KEY_ALLOWED_MODES, value.allowedModes.map { it.name }.toSet())
            .putString(KEY_DEFAULT_MODE, value.defaultMode.name)
            .putInt(KEY_WIDTH, value.resolution.width)
            .putInt(KEY_HEIGHT, value.resolution.height)
            .putInt(KEY_DPI, value.resolution.dpi)
            .putBoolean(KEY_PIP_ON_HOME, value.pipOnHome)
            .putBoolean(KEY_AUTO_STOP_AFTER_RUN, value.autoStopAfterRun)
            .putBoolean(KEY_SHOW_SYSTEM_DECORATIONS, value.showSystemDecorations)
    }

    private companion object {
        const val PREFS = "izukijs_virtual_screen"
        const val KEY_ALLOWED_MODES = "allowed_modes"
        const val KEY_DEFAULT_MODE = "default_mode"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DPI = "dpi"
        const val KEY_PIP_ON_HOME = "pip_on_home"
        const val KEY_AUTO_STOP_AFTER_RUN = "auto_stop_after_run"
        const val KEY_SHOW_SYSTEM_DECORATIONS = "show_system_decorations"
        const val KEY_WAS_ACTIVE = "was_active"
        const val KEY_LAST_STOP_REASON = "last_stop_reason"
        const val KEY_LAST_STOP_AT = "last_stop_at"
    }
}
