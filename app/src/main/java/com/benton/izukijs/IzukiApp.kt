package com.benton.izukijs

import android.app.Application
import android.content.Context
import com.benton.izukijs.di.AppContainer
import com.benton.izukijs.i18n.LanguagePreferences
import timber.log.Timber

class IzukiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguagePreferences.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        container = AppContainer(this)
        container.init()
    }
}
