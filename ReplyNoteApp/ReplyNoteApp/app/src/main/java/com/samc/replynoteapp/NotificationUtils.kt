package com.samc.replynoteapp // <-- UPDATED PACKAGE

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.samc.replynoteapp.R // <-- UPDATED IMPORT FOR R

object NotificationUtils {
    const val NOTIFICATION_ID = 1
    const val CHANNEL_ID = "REPLY_NOTE_CHANNEL"
    const val DOC_CHANNEL_ID = "REPLY_NOTE_DOC_CHANNEL"
    const val KEY_TEXT_REPLY = "key_reply_note"
    const val ACTION_REPLY = "com.samc.replynoteapp.ACTION_REPLY" // <-- UPDATED ACTION
    const val REQUEST_CODE_REPLY = 100
    const val EXTRA_DOC_ID = "extra_doc_id"
    private const val DEFAULT_LAST_MESSAGE = "Ready for new note..."

    // Small status glyph (system will recolor it as needed)
    private val smallIconRes = R.drawable.ic_note_stasher_large
    private var cachedLargeIcon: Bitmap? = null
    private const val replyIconRes = android.R.drawable.ic_menu_send

    private fun loadLargeIcon(context: Context): Bitmap? {
        cachedLargeIcon?.takeIf { !it.isRecycled }?.let { return it }
        val res = context.resources
        cachedLargeIcon = BitmapFactory.decodeResource(res, R.drawable.ic_note_stasher_large)
            ?: BitmapFactory.decodeResource(res, R.drawable.ic_note_stasher_large1)
        return cachedLargeIcon
    }

    private fun buildReplyAction(context: Context, docId: String?): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Type your note...")
            .build()

        val replyIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_REPLY // Uses the updated action string
            docId?.let { putExtra(EXTRA_DOC_ID, it) }
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

        return replyAction
    }

    fun buildServiceNotification(context: Context, contentText: String = "Type note below"): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle("New Note")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setLargeIcon(loadLargeIcon(context))

        val stopIntent = Intent(context, NotificationReceiver::class.java).apply {
            action = NotificationReceiver.ACTION_DISABLE_ALL
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REPLY + 1,
            stopIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        builder.addAction(
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Turn off notifications",
                stopPendingIntent
            ).build()
        )

        return builder.build()
    }

    fun buildDocumentNotification(
        context: Context,
        doc: DocEntry,
        message: String?,
        pendingCount: Int = 0
    ): Notification {
        val displayMessage = message?.takeIf { it.isNotBlank() } ?: DEFAULT_LAST_MESSAGE
        val replyAction = buildReplyAction(context, doc.docId)

        val builder = NotificationCompat.Builder(context, DOC_CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(doc.alias.ifBlank { doc.title }.ifBlank { "ReplyNote" })
            .setContentText(displayMessage)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setSubText("ReplyNote")
            .setOnlyAlertOnce(true)
            .setLargeIcon(loadLargeIcon(context))
            .addAction(replyAction)

        if (pendingCount > 0) {
            builder.setContentInfo("Pending $pendingCount")
        }

        return builder.build()
    }

    fun notifyDocument(
        context: Context,
        doc: DocEntry,
        message: String?,
        pendingCount: Int = MessageQueueManager.getPendingCountForDoc(context, doc.docId)
    ) {
        val notification = buildDocumentNotification(context, doc, message, pendingCount)
        NotificationManagerCompat.from(context).notify(doc.docId.hashCode(), notification)
    }

    fun refreshDocumentNotification(context: Context, docId: String) {
        val doc = SharedPreferencesHelper.loadDocEntries(context).firstOrNull { it.docId == docId }
            ?: DocEntry(docId = docId, alias = docId.takeLast(6))
        val message = SharedPreferencesHelper.loadDocLastMessage(context, docId)
        notifyDocument(context, doc, message)
    }
}
