package com.samc.replynoteapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class NotificationService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d("NotificationService", "Service Created")
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NotificationService", "Service Started")
        try {
            // Get all documents with notifications enabled
            val docs = SharedPreferencesHelper.loadDocEntries(this)
            val enabledDocs = docs.filter { doc ->
                SharedPreferencesHelper.loadDocNotificationEnabled(this, doc.docId)
            }
            
            if (enabledDocs.isEmpty()) {
                Log.w("NotificationService", "No documents have notifications enabled")
                stopSelf()
                return START_NOT_STICKY
            }
            
            // Create the foreground notification (required for foreground service)
            val foregroundNotification = NotificationUtils.buildServiceNotification(
                this, 
                "Managing ${enabledDocs.size} document notification${if (enabledDocs.size > 1) "s" else ""}"
            )
            startForeground(NotificationUtils.NOTIFICATION_ID, foregroundNotification)
            
            // Create individual notifications for each enabled document
            val notificationManager = NotificationManagerCompat.from(this)
            enabledDocs.forEach { doc ->
                if (!doc.docId.startsWith("unsynced")) {
                    val lastMessage = SharedPreferencesHelper.loadDocLastMessage(this, doc.docId)
                    val pendingCount = MessageQueueManager.getPendingCountForDoc(this, doc.docId)
                    val notification = NotificationUtils.buildDocumentNotification(this, doc, lastMessage, pendingCount)
                    val notificationId = doc.docId.hashCode()
                    notificationManager.notify(notificationId, notification)
                    Log.d("NotificationService", "Created notification for document: ${doc.alias}")
                }
            }
            
            Log.d("NotificationService", "Created notifications for ${enabledDocs.size} documents")
        } catch (e: Exception) {
            Log.e("NotificationService", "Error starting foreground service or building notifications", e)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d("NotificationService", "Service Destroyed")
        // Remove all document notifications when service stops
        val notificationManager = NotificationManagerCompat.from(this)
        val docs = SharedPreferencesHelper.loadDocEntries(this)
        docs.forEach { doc ->
            notificationManager.cancel(doc.docId.hashCode())
        }
        notificationManager.cancel(NotificationUtils.NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager == null) {
                Log.e("NotificationService", "Failed to get NotificationManager for channel creation")
                return
            }
            
            // Main service channel
            val serviceChannel = NotificationChannel(
                NotificationUtils.CHANNEL_ID, 
                "Reply Notes Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service notification for Reply Notes"
            }
            
            // Document notifications channel
            val docChannel = NotificationChannel(
                NotificationUtils.DOC_CHANNEL_ID,
                "Document Notifications", 
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Individual document reply notifications"
            }
            
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(docChannel)
            Log.d("NotificationService", "Notification Channels Created/Updated")
        }
    }
}
