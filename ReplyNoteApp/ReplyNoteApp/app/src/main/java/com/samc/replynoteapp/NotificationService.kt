package com.samc.replynoteapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class NotificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d("NotificationService", "Service Created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NotificationService", "Service Started")
        try {
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(this)
            val initialMessage = if (!appsScriptUrl.isNullOrBlank()) {
                "Ready for new note..."
            } else {
                "Error: Setup App URL in app first!"
            }
            val notification = NotificationUtils.buildNotification(this, initialMessage)
            startForeground(NotificationUtils.NOTIFICATION_ID, notification)
            Log.d("NotificationService", "startForeground called with message: $initialMessage")
        } catch (e: Exception) {
            Log.e("NotificationService", "Error starting foreground service or building notification", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d("NotificationService", "Service Destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Reply Notes Channel"
            val descriptionText = "Channel for the persistent reply note"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(NotificationUtils.CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager? =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            if (notificationManager == null) {
                Log.e("NotificationService", "Failed to get NotificationManager for channel creation")
                return
            }
            notificationManager.createNotificationChannel(channel)
            Log.d("NotificationService", "Notification Channel Created/Updated")
        }
    }
}