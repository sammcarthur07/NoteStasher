package com.samc.replynoteapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MessageRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val delayMs = MessageQueueManager.processQueue(applicationContext)
        val hasPending = MessageQueueManager.hasPendingMessages(applicationContext)

        if (hasPending) {
            val nextDelay = delayMs ?: 30_000L
            MessageRetryScheduler.scheduleNext(applicationContext, nextDelay)
        }

        return Result.success()
    }
}
