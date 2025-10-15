package com.save.me

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Device booted: initializing app state.")

            try {
                CommandManager.initialize(context)
                UploadManager.init(context)
                NotificationHelper.createChannel(context)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Error during boot initialization", e)
            }
        }
    }
}