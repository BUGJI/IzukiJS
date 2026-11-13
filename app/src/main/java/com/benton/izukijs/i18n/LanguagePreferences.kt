package com.benton.izukijs.i18n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/**
 * 语言偏好的持久化与 Context 包装。
 *
 * 在 Activity / Application 的 `attachBaseContext` 阶段直接读取 SharedPreferences，
 * 无需等待依赖容器初始化，确保资源在最早时机就按所选语言解析。
 */
object LanguagePreferences {

    private const val PREFS = "izukijs_language"
    private const val KEY_LANGUAGE = "app_language"

    fun readTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    fun writeTag(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, tag)
            .apply()
    }

    /** 按已保存的偏好包装 [context]。 */
    fun wrap(context: Context): Context = wrap(context, AppLanguage.fromTag(readTag(context)))

    /** 按指定语言包装 [context]；[AppLanguage.SYSTEM] 且系统语言受支持时原样返回。 */
    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = resolveLocale(context, language) ?: return context
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            @Suppress("DEPRECATION")
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * 就地更新某个 Context 的资源语言（通常传 Application），使不经过 Activity 的 Context
     * （如前台服务的通知文案）也立即使用所选语言。
     */
    fun applyToContext(context: Context, language: AppLanguage) {
        val locale = resolveLocale(context, language)
            ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            @Suppress("DEPRECATION")
            setLayoutDirection(locale)
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    /**
     * 跟随系统时，只有中文 / 英文走系统资源；其他语言回退英文，
     * 避免默认资源（中文）让非中英用户看到完全不相符的界面。
     */
    private fun resolveLocale(context: Context, language: AppLanguage): Locale? {
        if (language != AppLanguage.SYSTEM) return language.locale
        val system = context.resources.configuration.locales[0]
        return if (system.language == "zh" || system.language == "en") null else Locale.ENGLISH
    }
}
