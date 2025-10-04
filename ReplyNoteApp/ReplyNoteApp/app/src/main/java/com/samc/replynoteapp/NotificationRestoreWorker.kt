package com.samc.replynoteapp

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NotificationRestoreWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val docs = SharedPreferencesHelper.loadDocEntries(context)
        val enabledDocs = docs.filter { doc ->
            SharedPreferencesHelper.loadDocNotificationEnabled(context, doc.docId) &&
                !doc.docId.startsWith("unsynced")
        }

        if (enabledDocs.isNotEmpty()) {
            val intent = Intent(context, NotificationService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        if (MessageQueueManager.hasPendingMessages(context)) {
            MessageRetryScheduler.enqueue(context)
        }

        return Result.success()
    }
}
