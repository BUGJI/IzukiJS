package com.benton.izukijs.di

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 应用依赖图。
 *
 * 四张具名图按依赖方向排列：Core -> Control -> Runtime -> Mcp，最后组装兼容门面。
 * 使用 Koin 而不是 Hilt：当前 Kotlin 2.4.x 与 KSP 版本的匹配仍在演进，Koin 不引入
 * 注解处理器，可以在不改构建链路的前提下先完成图拆分。
 */
val appModules = module {
    single { CoreContainer(androidContext() as Application) }
    single { ControlContainer(androidContext() as Application, get()) }
    single { RuntimeContainer(androidContext() as Application, get(), get()) }
    single { McpContainer(androidContext() as Application, get(), get(), get()) }
    single { AppContainer(androidContext() as Application, get(), get(), get(), get()) }
}
