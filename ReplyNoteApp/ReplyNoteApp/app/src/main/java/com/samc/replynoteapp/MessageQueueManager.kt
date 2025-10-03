package com.samc.replynoteapp

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

data class QueuedMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val isRich: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val syncStatus: SyncStatus = SyncStatus.PENDING
)

enum class SyncStatus {
    PENDING,    // Waiting to be synced
    SYNCING,    // Currently being synced
    SYNCED,     // Successfully synced
    FAILED      // Sync failed
}

object MessageQueueManager {
    private const val TAG = "MessageQueueManager"
    private const val QUEUE_PREFS = "message_queue_prefs"
    private const val QUEUE_KEY = "queued_messages"
    private val gson = Gson()
    
    fun addToQueue(context: Context, docId: String, content: String, isRich: Boolean): String {
        val message = QueuedMessage(
            docId = docId,
            content = content,
            isRich = isRich
        )
        
        val queue = loadQueue(context).toMutableList()
        queue.add(message)
        saveQueue(context, queue)
        
        Log.d(TAG, "Added message to queue for doc: $docId")
        
        // Try to sync immediately if doc has URL
        if (docId.length > 20) { // Valid doc ID length
            GlobalScope.launch {
                syncMessage(context, message)
            }
        }
        
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
    
    fun updateMessageStatus(context: Context, messageId: String, status: SyncStatus) {
        val queue = loadQueue(context).toMutableList()
        val index = queue.indexOfFirst { it.id == messageId }
        
        if (index != -1) {
            queue[index] = queue[index].copy(syncStatus = status)
            saveQueue(context, queue)
        }
    }
    
    suspend fun syncMessage(context: Context, message: QueuedMessage) {
        if (message.docId.startsWith("unsynced")) {
            // Can't sync unsynced doc
            return
        }
        
        updateMessageStatus(context, message.id, SyncStatus.SYNCING)
        
        val result = if (message.isRich) {
            WebAppHubClient.appendBlocks(context, message.docId, message.content)
        } else {
            WebAppHubClient.appendPlain(context, message.docId, message.content)
        }
        
        if (result.isSuccess) {
            updateMessageStatus(context, message.id, SyncStatus.SYNCED)
            Log.d(TAG, "Message synced successfully: ${message.id}")
            
            // Update history status to synced
            updateHistoryStatus(context, message.timestamp, "sent")
        } else {
            updateMessageStatus(context, message.id, SyncStatus.FAILED)
            Log.e(TAG, "Failed to sync message: ${message.id}")
            
            // Update history status to failed
            updateHistoryStatus(context, message.timestamp, "failed")
        }
    }
    
    private fun updateHistoryStatus(context: Context, timestamp: Long, status: String) {
        try {
            val history = SharedPreferencesHelper.loadMessageHistory(context)
            val index = history.indexOfFirst { 
                kotlin.math.abs(it.timestamp - timestamp) < 5000 // Within 5 seconds
            }
            if (index != -1) {
                SharedPreferencesHelper.updateMessageHistoryStatus(context, index, status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating history status", e)
        }
    }
    
    suspend fun syncAllForDoc(context: Context, docId: String) {
        val queue = loadQueue(context)
        val pendingMessages = queue.filter { 
            it.docId == docId && it.syncStatus != SyncStatus.SYNCED 
        }
        
        Log.d(TAG, "Syncing ${pendingMessages.size} messages for doc: $docId")
        
        for (message in pendingMessages) {
            syncMessage(context, message)
            delay(500) // Small delay between messages
        }
    }
    
    fun getPendingCountForDoc(context: Context, docId: String): Int {
        return loadQueue(context).count { 
            it.docId == docId && it.syncStatus == SyncStatus.PENDING 
        }
    }
    
    fun clearSyncedMessages(context: Context) {
        val queue = loadQueue(context)
        val remaining = queue.filter { it.syncStatus != SyncStatus.SYNCED }
        saveQueue(context, remaining)
        
        Log.d(TAG, "Cleared ${queue.size - remaining.size} synced messages")
    }
}