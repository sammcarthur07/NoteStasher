package com.samc.replynoteapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object MessageSender {
    private val okHttpClient = OkHttpClient()
    
    private fun appendStatsParams(url: String, statsTop: Boolean, statsBottom: Boolean): String {
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + "statsTop=" + statsTop + "&statsBottom=" + statsBottom
    }

    suspend fun sendMessageToGoogleDoc(
        context: Context,
        message: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d("MessageSender", "=== sendMessageToGoogleDoc called ===")
            Log.d("MessageSender", "Message to send: '$message' (length: ${message.length})")
            
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
            if (appsScriptUrl.isNullOrBlank()) {
                Log.e("MessageSender", "Apps Script URL not configured")
                return@withContext Result.failure(Exception("Apps Script URL not configured"))
            }
            
            Log.d("MessageSender", "Sending to Apps Script URL: $appsScriptUrl")
            
            // Include stats flags as URL query params so server can enforce insertion points even for plain text
            val statsTop = SharedPreferencesHelper.loadStatsTop(context)
            val statsBottom = SharedPreferencesHelper.loadStatsBottom(context)
            val urlWithParams = appendStatsParams(appsScriptUrl, statsTop, statsBottom)

            // Send to Google Doc as plain text body
            val requestBody = message.toRequestBody("text/plain; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(urlWithParams)
                .post(requestBody)
                .build()
            
            Log.d("MessageSender", "Executing HTTP request...")
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val responseBodyStr = response.body?.string() ?: "No response body"
                Log.e("MessageSender", "Apps Script POST failed: ${response.code} ${response.message}\nBody: $responseBodyStr")
                return@withContext Result.failure(IOException("Failed to send: ${response.code}"))
            }
            
            val responseBodyStr = response.body?.string() ?: "No response body"
            Log.d("MessageSender", "Apps Script POST successful: $responseBodyStr")
            
            // Save to history on successful send
            Log.d("MessageSender", "Saving message to history...")
            SharedPreferencesHelper.saveMessageToHistory(context, message)
            Log.d("MessageSender", "Message saved to history successfully")
            
            return@withContext Result.success("Message sent successfully")
            
        } catch (e: IOException) {
            Log.e("MessageSender", "Network error sending to Apps Script", e)
            return@withContext Result.failure(e)
        } catch (e: Exception) {
            Log.e("MessageSender", "Error sending to Apps Script", e)
            return@withContext Result.failure(e)
        }
    }

    // Apply stats settings immediately via Apps Script endpoint
    suspend fun applyStatsSettings(
        context: Context,
        statsTop: Boolean,
        statsBottom: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
                ?: return@withContext Result.failure(Exception("Apps Script URL not configured"))
            val payload = "{" +
                    "\"mode\":\"applyStatsSettings\"," +
                    "\"statsTop\":" + statsTop + "," +
                    "\"statsBottom\":" + statsBottom +
                    "}"
            val rb = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder().url(appsScriptUrl).post(rb).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d("MessageSender", "applyStatsSettings resp=${resp.code} body=${body}")
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            Result.success(body)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // Install the time-driven trigger for updateDocChangeTimer (every N minutes)
    suspend fun installStatsTrigger(
        context: Context,
        minutes: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
                ?: return@withContext Result.failure(Exception("Apps Script URL not configured"))
            val payload = "{" +
                    "\"mode\":\"setupTrigger\"," +
                    "\"minutes\":" + minutes +
                    "}"
            val rb = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder().url(appsScriptUrl).post(rb).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d("MessageSender", "installStatsTrigger resp=${resp.code} body=${body}")
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            Result.success(body)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    // Unified config update: timezone, auto-update toggle, minutes
    suspend fun setStatsConfig(
        context: Context,
        timezone: String? = null,
        autoUpdateEnabled: Boolean? = null,
        minutes: Int? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
                ?: return@withContext Result.failure(Exception("Apps Script URL not configured"))
            val json = buildString {
                append("{")
                append("\"mode\":\"setConfig\"")
                timezone?.let { append(",\"timezone\":\"" + it.replace("\\", "\\\\").replace("\"","\\\"") + "\"") }
                autoUpdateEnabled?.let { append(",\"autoUpdateEnabled\":" + it) }
                minutes?.let { append(",\"minutes\":" + it) }
                append("}")
            }
            val rb = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder().url(appsScriptUrl).post(rb).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d("MessageSender", "setStatsConfig resp=${resp.code} body=${body}")
            if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            Result.success(body)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
