package com.samc.replynoteapp // <-- UPDATED PACKAGE

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.samc.replynoteapp.R // <-- UPDATED IMPORT FOR R

object NotificationUtils {
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "REPLY_NOTE_CHANNEL"
    const val KEY_TEXT_REPLY = "key_reply_note"
    const val ACTION_REPLY = "com.samc.replynoteapp.ACTION_REPLY" // <-- UPDATED ACTION
    const val REQUEST_CODE_REPLY = 100

    // Replace 'sexy_icon' if your icon name is different
    private val smallIconRes = R.drawable.sexy_icon
    private const val replyIconRes = android.R.drawable.ic_menu_send

    fun buildNotification(context: Context, contentText: String = "Type note below"): Notification {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Type your note...")
            .build()

        val replyIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_REPLY // Uses the updated action string
        }
        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REPLY,
            replyIntent,
            intentFlags
        )

        val replyAction = NotificationCompat.Action.Builder(
            replyIconRes,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle("New Note") // This method should exist
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .addAction(replyAction)

        return builder.build()
    }
}
