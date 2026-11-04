package com.benton.izukijs.core.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences + StateFlow 的通用骨架。
 *
 * 子类只需实现 [read] / [write]，并可通过 [normalize] 在保存前修正取值。
 * 状态读取延迟到首次访问，避免基类构造阶段调用尚未完成初始化的子类成员；
 * 因此 [read] 只能依赖 [prefs] 与静态常量。
 */
abstract class PrefsStateStore<T>(
    context: Context,
    prefsName: String,
    private val defaultValue: T,
) {

    protected val prefs: SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private val mutableState: MutableStateFlow<T> by lazy { MutableStateFlow(read()) }

    val state: StateFlow<T> by lazy { mutableState.asStateFlow() }

    fun current(): T = mutableState.value

    fun save(value: T) {
        val next = normalize(value)
        mutableState.value = next
        prefs.edit().also { write(it, next) }.apply()
    }

    /** 清空持久化并回到 [defaultValue]。 */
    fun clear() {
        prefs.edit().clear().apply()
        mutableState.value = defaultValue
    }

    protected open fun normalize(value: T): T = value

    protected abstract fun read(): T

    protected abstract fun write(editor: SharedPreferences.Editor, value: T)
}
