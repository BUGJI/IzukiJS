package com.benton.izukijs.i18n

import java.util.Locale

/** 应用内可选语言。[tag] 为 null 表示跟随系统。 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    CHINESE("zh"),
    ENGLISH("en"),
    ;

    val locale: Locale? get() = tag?.let(Locale::forLanguageTag)

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
