package com.vibecode.notestasher

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MessageRetryScheduler {
    private const val WORK_NAME = "queued_messages_retry"
    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueue(context: Context) {
        schedule(context, 0L, ExistingWorkPolicy.KEEP)
    }

    fun scheduleNext(context: Context, delayMs: Long?) {
        if (delayMs == null) {
            return
        }
        schedule(context, delayMs.coerceAtLeast(0L), ExistingWorkPolicy.REPLACE)
    }

    private fun schedule(context: Context, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<MessageRetryWorker>()
            .setConstraints(networkConstraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
