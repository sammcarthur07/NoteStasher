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
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NotificationReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient()

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("NotificationReceiver", "=== onReceive called ===")
        Log.d("NotificationReceiver", "Context: ${context != null}, Intent: ${intent != null}, Action: ${intent?.action}")
        
        if (context == null || intent == null || intent.action != NotificationUtils.ACTION_REPLY) {
            Log.w("NotificationReceiver", "Invalid context, intent, or action. Expected action: ${NotificationUtils.ACTION_REPLY}")
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationUtils.KEY_TEXT_REPLY)?.toString()
        Log.d("NotificationReceiver", "Reply Received: '$replyText' (length: ${replyText?.length ?: 0})")

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(appContext)

        if (appsScriptUrl.isNullOrBlank()) {
            Log.e("NotificationReceiver", "Apps Script URL not configured in app settings.")
            Toast.makeText(appContext, "Error: Apps Script URL not set. Configure in app.", Toast.LENGTH_LONG).show()
            refreshNotification(appContext, "URL Not Set! Configure in app.")
            pendingResult.finish()
            return
        }

        if (!replyText.isNullOrEmpty()) {
            scope.launch {
                var success = false
                var statusMessage = "Failed to send note"
                try {
                    Log.d("NotificationReceiver", "Attempting to send text to Google Doc...")
                    sendTextToGoogleDoc(replyText, appsScriptUrl)
                    Log.d("NotificationReceiver", "Successfully sent '$replyText' to Apps Script: $appsScriptUrl")
                    success = true
                    statusMessage = "Sent: $replyText"
                    // Save to history on successful send
                    Log.d("NotificationReceiver", "Saving message to history...")
                    SharedPreferencesHelper.saveMessageToHistory(appContext, replyText)
                    Log.d("NotificationReceiver", "Message saved to history successfully")
                } catch (e: IOException) {
                    Log.e("NotificationReceiver", "Network error sending to Apps Script", e)
                    statusMessage = "Network Error"
                } catch (e: Exception) {
                    Log.e("NotificationReceiver", "Error sending to Apps Script", e)
                    statusMessage = "Send Error"
                } finally {
                    refreshNotification(appContext, statusMessage)
                    pendingResult.finish()
                    Log.d("NotificationReceiver", "goAsync finished.")
                }
            }
        } else {
            Log.w("NotificationReceiver", "Empty reply text, only refreshing notification.")
            refreshNotification(appContext, "Ready for new note...")
            pendingResult.finish()
        }
    }

    private fun refreshNotification(context: Context, newContentText: String) {
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val newNotification = NotificationUtils.buildNotification(context, newContentText)
            notificationManager.notify(NotificationUtils.NOTIFICATION_ID, newNotification)
            Log.d("NotificationReceiver", "Notification refreshed with text: $newContentText")
        } catch (e: SecurityException) {
            Log.e("NotificationReceiver", "Permission error refreshing notification", e)
        } catch (e: Exception) {
            Log.e("NotificationReceiver", "General error refreshing notification", e)
        }
    }

    @Throws(IOException::class)
    private fun sendTextToGoogleDoc(text: String, url: String) {
        Log.d("NotificationReceiver", "sendTextToGoogleDoc: Preparing request...")
        val requestBody = text.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        Log.d("NotificationReceiver", "sendTextToGoogleDoc: Executing HTTP request...")
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBodyStr = response.body?.string() ?: "No response body"
                Log.e("NotificationReceiver", "Apps Script POST failed: ${response.code} ${response.message}\nBody: $responseBodyStr")
                throw IOException("Unexpected code ${response.code} - $responseBodyStr")
            } else {
                val responseBodyStr = response.body?.string() ?: "No response body"
                Log.d("NotificationReceiver", "Apps Script POST successful: $responseBodyStr")
            }
        }
    }
}