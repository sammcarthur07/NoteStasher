package com.samc.replynoteapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISABLE_ALL = "com.samc.replynoteapp.ACTION_DISABLE_ALL"
        const val ACTION_NOTIFICATIONS_UPDATED = "com.samc.replynoteapp.ACTION_NOTIFICATIONS_UPDATED"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryDelays = listOf(0L, 5_000L, 10_000L, 15_000L, 30_000L)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            return
        }
        val appContext = context.applicationContext
        val action = intent.action

        if (action == ACTION_DISABLE_ALL) {
            disableAllNotifications(appContext)
            return
        }

        Log.d("NotificationReceiver", "=== onReceive called ===")
        Log.d("NotificationReceiver", "Context: true, Intent: true, Action: ${intent.action}")
        
        if (action != NotificationUtils.ACTION_REPLY) {
            Log.w("NotificationReceiver", "Unexpected action: ${intent.action}")
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationUtils.KEY_TEXT_REPLY)?.toString()
        val docId = intent.getStringExtra(NotificationUtils.EXTRA_DOC_ID)
        Log.d("NotificationReceiver", "Reply Received: '$replyText' (length: ${replyText?.length ?: 0}) for docId=$docId")

        val pendingResult = goAsync()
        if (replyText.isNullOrBlank()) {
            Log.w("NotificationReceiver", "Empty reply text, only refreshing notification.")
            docId?.let { refreshDocNotificationIfEnabled(appContext, it) }
            pendingResult.finish()
            return
        }

        if (docId.isNullOrBlank()) {
            Log.e("NotificationReceiver", "Missing docId in reply intent")
            Toast.makeText(appContext, "Unable to send: missing document info", Toast.LENGTH_LONG).show()
            pendingResult.finish()
            return
        }

        scope.launch {
            val docEntries = SharedPreferencesHelper.loadDocEntries(appContext)
            val doc = docEntries.firstOrNull { it.docId == docId }

            if (doc == null || doc.docId.startsWith("unsynced")) {
                Log.w("NotificationReceiver", "Doc $docId is unsynced or missing; queueing reply")
                handleQueueFallback(appContext, docId, replyText, "Document not synced")
                pendingResult.finish()
                return@launch
            }

            var success = false
            var lastError: String? = null

            for (delayMs in retryDelays) {
                if (delayMs > 0) delay(delayMs)
                val result = WebAppHubClient.appendPlain(appContext, docId, replyText)
                if (result.isSuccess) {
                    success = true
                    break
                } else {
                    lastError = result.exceptionOrNull()?.message ?: "Unknown error"
                    Log.w("NotificationReceiver", "Retry after failure for doc $docId: $lastError")
                }
            }

            if (success) {
                SharedPreferencesHelper.saveDocLastMessage(appContext, docId, replyText)
                SharedPreferencesHelper.saveMessageToHistory(appContext, replyText)
                updateHistoryStatus(appContext, "sent")
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Sent to ${doc.alias.ifBlank { doc.title }}", Toast.LENGTH_SHORT).show()
                }
                refreshDocNotificationIfEnabled(appContext, docId)
            } else {
                val errorText = lastError ?: "Send failed"
                SharedPreferencesHelper.saveDocLastMessage(appContext, docId, "Retry scheduled: $errorText")
                SharedPreferencesHelper.saveMessageToHistory(appContext, replyText)
                updateHistoryStatus(appContext, "pending")
                handleQueueFallback(appContext, docId, replyText, errorText)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Send failed, will retry", Toast.LENGTH_LONG).show()
                }
                refreshDocNotificationIfEnabled(appContext, docId)
            }

            pendingResult.finish()
            Log.d("NotificationReceiver", "goAsync finished.")
        }
    }

    private suspend fun handleQueueFallback(context: Context, docId: String, replyText: String, errorText: String) {
        val messageId = MessageQueueManager.addToQueue(context, docId, replyText, false, replyText.take(200))
        MessageRetryScheduler.enqueue(context)
        Log.d("NotificationReceiver", "Queued message $messageId for doc $docId after error: $errorText")
    }

    private fun updateHistoryStatus(context: Context, status: String) {
        SharedPreferencesHelper.updateMessageHistoryStatus(context, 0, status)
    }

    private fun disableAllNotifications(context: Context) {
        val docs = SharedPreferencesHelper.loadDocEntries(context)
        docs.forEach { doc ->
            SharedPreferencesHelper.saveDocNotificationEnabled(context, doc.docId, false)
            NotificationManagerCompat.from(context).cancel(doc.docId.hashCode())
        }
        NotificationManagerCompat.from(context).cancel(NotificationUtils.NOTIFICATION_ID)
        context.stopService(Intent(context, NotificationService::class.java))
        context.sendBroadcast(Intent(ACTION_NOTIFICATIONS_UPDATED))
        NotificationStateNotifier.notifyChanged()
        Log.d("NotificationReceiver", "All document notifications disabled via notification action")
    }

    private fun refreshDocNotificationIfEnabled(context: Context, docId: String) {
        if (docId.startsWith("unsynced")) return
        if (SharedPreferencesHelper.loadDocNotificationEnabled(context, docId)) {
            NotificationUtils.refreshDocumentNotification(context, docId)
        }
    }
}
