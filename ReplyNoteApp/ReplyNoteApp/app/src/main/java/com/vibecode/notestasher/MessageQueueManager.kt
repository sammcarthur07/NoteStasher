package com.vibecode.notestasher

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class QueuedMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val isRich: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val historySnippet: String? = null
)

enum class SyncStatus {
    PENDING,    // Waiting to be synced
    SYNCING,    // Currently being synced
    SYNCED,     // Successfully synced
    FAILED      // Sync failed (will retry)
}

object MessageQueueManager {
    private const val TAG = "MessageQueueManager"
    private const val QUEUE_PREFS = "message_queue_prefs"
    private const val QUEUE_KEY = "queued_messages"
    private val gson = Gson()
    private val retrySchedule = longArrayOf(5_000L, 10_000L, 15_000L, 30_000L)

    fun addToQueue(
        context: Context,
        docId: String,
        content: String,
        isRich: Boolean,
        historySnippet: String? = null
    ): String {
        val now = System.currentTimeMillis()
        val message = QueuedMessage(
            docId = docId,
            content = content,
            isRich = isRich,
            timestamp = now,
            nextAttemptAt = now,
            historySnippet = historySnippet
        )

        val queue = loadQueue(context).toMutableList()
        queue.add(message)
        saveQueue(context, queue)

        Log.d(TAG, "Added message to queue for doc: $docId")
        MessageRetryScheduler.enqueue(context)

        return message.id
    }

    fun loadQueue(context: Context): List<QueuedMessage> {
        val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(QUEUE_KEY, "[]")

        return try {
            val type = object : TypeToken<List<QueuedMessage>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading queue", e)
            emptyList()
        }
    }

    private fun saveQueue(context: Context, queue: List<QueuedMessage>) {
        val prefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val json = gson.toJson(queue)
        prefs.edit().putString(QUEUE_KEY, json).apply()
    }

    suspend fun processQueue(context: Context): Long? = withContext(Dispatchers.IO) {
        val queue = loadQueue(context).toMutableList()
        if (queue.isEmpty()) {
            return@withContext null
        }

        val now = System.currentTimeMillis()
        var earliestNext: Long? = null

        for (index in queue.indices) {
            val message = queue[index]
            if (message.syncStatus == SyncStatus.SYNCED) {
                continue
            }

            if (message.nextAttemptAt <= now) {
                val updated = attemptSend(context, message)
                queue[index] = updated
            } else {
                val delta = message.nextAttemptAt - now
                earliestNext = earliestNext?.let { minOf(it, delta) } ?: delta
            }
        }

        val remaining = queue.filterNot { it.syncStatus == SyncStatus.SYNCED }
        saveQueue(context, remaining)

        // Determine earliest upcoming attempt among remaining messages
        val next = earliestNext ?: remaining.filter { it.syncStatus != SyncStatus.SYNCED }
            .minOfOrNull { maxOf(0L, it.nextAttemptAt - System.currentTimeMillis()) }

        next
    }

    private suspend fun attemptSend(context: Context, message: QueuedMessage): QueuedMessage {
        if (message.docId.startsWith("unsynced")) {
            val nextAttempt = System.currentTimeMillis() + retrySchedule.last()
            return message.copy(
                syncStatus = SyncStatus.PENDING,
                nextAttemptAt = nextAttempt
            )
        }

        val result = if (message.isRich) {
            WebAppHubClient.appendBlocks(context, message.docId, message.content)
        } else {
            WebAppHubClient.appendPlain(context, message.docId, message.content)
        }

        return if (result.isSuccess) {
            Log.d(TAG, "Message synced successfully: ${message.id}")
            updateHistoryStatus(context, message, "sent")
            if (!message.docId.startsWith("unsynced")) {
                val preview = message.historySnippet?.take(500)
                    ?: if (message.isRich) "Queued note sent" else message.content.take(500)
                SharedPreferencesHelper.saveDocLastMessage(context, message.docId, preview)
                if (SharedPreferencesHelper.loadDocNotificationEnabled(context, message.docId)) {
                    NotificationUtils.refreshDocumentNotification(context, message.docId)
                }
            }
            message.copy(syncStatus = SyncStatus.SYNCED, nextAttemptAt = System.currentTimeMillis())
        } else {
            val error = result.exceptionOrNull()?.message ?: "Unknown error"
            Log.e(TAG, "Failed to sync message: ${message.id} -> $error")
            val attempts = message.attempts + 1
            val delayMs = computeNextDelay(attempts)
            val nextAttempt = System.currentTimeMillis() + delayMs
            updateHistoryStatus(context, message, "failed")
            if (!message.docId.startsWith("unsynced")) {
                SharedPreferencesHelper.saveDocLastMessage(context, message.docId, "Retry scheduled: $error")
                if (SharedPreferencesHelper.loadDocNotificationEnabled(context, message.docId)) {
                    NotificationUtils.refreshDocumentNotification(context, message.docId)
                }
            }
            message.copy(
                syncStatus = SyncStatus.FAILED,
                attempts = attempts,
                nextAttemptAt = nextAttempt,
                lastError = error
            )
        }
    }

    private fun computeNextDelay(attempts: Int): Long {
        return if (attempts <= 0) retrySchedule.first() else retrySchedule.getOrElse(attempts) { retrySchedule.last() }
    }

    private fun updateHistoryStatus(context: Context, message: QueuedMessage, status: String) {
        try {
            val history = SharedPreferencesHelper.loadMessageHistory(context)
            val indexByTime = history.indexOfFirst {
                kotlin.math.abs(it.timestamp - message.timestamp) < 10_000
            }
            val index = if (indexByTime != -1) {
                indexByTime
            } else {
                message.historySnippet?.let { snippet ->
                    history.indexOfFirst { it.text == snippet }
                } ?: -1
            }
            if (index != -1) {
                SharedPreferencesHelper.updateMessageHistoryStatus(context, index, status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating history status", e)
        }
    }

    suspend fun syncAllForDoc(context: Context, docId: String) {
        withContext(Dispatchers.IO) {
            val queue = loadQueue(context).toMutableList()
            val now = System.currentTimeMillis()
            var changed = false
            for (i in queue.indices) {
                val msg = queue[i]
                if (msg.docId == docId && msg.syncStatus != SyncStatus.SYNCED) {
                    queue[i] = msg.copy(nextAttemptAt = now, syncStatus = SyncStatus.PENDING)
                    changed = true
                }
            }
            if (changed) {
                saveQueue(context, queue)
                processQueue(context)
                MessageRetryScheduler.enqueue(context)
            }
        }
    }

    fun getPendingCountForDoc(context: Context, docId: String): Int {
        return loadQueue(context).count { 
            it.docId == docId && it.syncStatus != SyncStatus.SYNCED 
        }
    }

    fun hasPendingMessages(context: Context): Boolean {
        return loadQueue(context).any { it.syncStatus != SyncStatus.SYNCED }
    }

    fun clearSyncedMessages(context: Context) {
        val queue = loadQueue(context)
        val remaining = queue.filter { it.syncStatus != SyncStatus.SYNCED }
        saveQueue(context, remaining)

        Log.d(TAG, "Cleared ${queue.size - remaining.size} synced messages")
    }
}
