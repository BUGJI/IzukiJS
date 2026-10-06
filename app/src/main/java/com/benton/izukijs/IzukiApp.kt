package com.benton.izukijs

import android.app.Application
import com.benton.izukijs.di.AppContainer
import timber.log.Timber

class IzukiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        container = AppContainer(this)
        container.init()
    }
}
