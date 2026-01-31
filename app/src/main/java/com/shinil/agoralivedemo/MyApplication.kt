package com.shinil.agoralivedemo

import android.app.Application
import com.shinil.agoralivedemo.util.ConnectionLifecycleManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application() {
    @Inject
    lateinit var connectionLifecycleManager: ConnectionLifecycleManager

    override fun onCreate() {
        super.onCreate()
        connectionLifecycleManager.initialize()
    }
}