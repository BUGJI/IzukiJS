package com.benton.izukijs

import android.app.Application
import android.content.Context
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.di.appModules
import com.benton.izukijs.i18n.LanguagePreferences
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class IzukiApp : Application(), KoinComponent {

    /**
     * 依赖门面由 Koin 注入。
     *
     * 保留 `container` 字段是为了不一次性改动所有 `Context.appContainer` 调用点；
     * 新代码优先使用 `container.core` / `container.control` / `container.runtime` 等具名图。
     */
    val container: AppContainer by inject()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@IzukiApp)
            modules(appModules)
        }
        container.init()
    }
}
