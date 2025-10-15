package com.save.me

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        UploadManager.init(this)
        CommandManager.initialize(this)
    }
}