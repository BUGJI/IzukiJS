package com.benton.izukijs.i18n

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用语言偏好：SharedPreferences 持久化 + 内存缓存，供设置页读取与切换。 */
class LanguageRepository(context: Context) {

    private val appContext = context.applicationContext

    private val _language = MutableStateFlow(AppLanguage.fromTag(LanguagePreferences.readTag(appContext)))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun current(): AppLanguage = _language.value

    fun save(value: AppLanguage) {
        _language.value = value
        LanguagePreferences.writeTag(appContext, value.tag)
        LanguagePreferences.applyToContext(appContext, value)
    }
}
