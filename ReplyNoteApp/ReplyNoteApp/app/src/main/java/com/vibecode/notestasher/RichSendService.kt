package com.vibecode.notestasher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class RichSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client: OkHttpClient by lazy {
        // Longer timeouts to allow Apps Script image processing
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val jsonPath = intent.getStringExtra(EXTRA_JSON_PATH) ?: return START_NOT_STICKY
                startForegroundIfNeeded(sessionId)
                if (!ACTIVE.containsKey(sessionId)) {
                    if (ACTIVE.size >= MAX_CONCURRENT) {
                        notifyError(sessionId, "Maximum concurrent sends reached (2). Try again later.")
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    ACTIVE[sessionId] = SessionState()
                    scope.launch { processSession(sessionId, jsonPath) }
                }
            }
            ACTION_CANCEL -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                ACTIVE[sessionId]?.cancelled = true
                updateNotification(sessionId, 0, 0, cancelled = true)
            }
            ACTION_RESUME -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val jsonPath = intent.getStringExtra(EXTRA_JSON_PATH) ?: return START_NOT_STICKY
                startForegroundIfNeeded(sessionId)
                if (!ACTIVE.containsKey(sessionId)) {
                    if (ACTIVE.size >= MAX_CONCURRENT) {
                        notifyError(sessionId, "Maximum concurrent sends reached (2). Try again later.")
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    ACTIVE[sessionId] = SessionState()
                    scope.launch { processSession(sessionId, jsonPath, resume = true) }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun processSession(sessionId: String, jsonPath: String, resume: Boolean = false) {
        try {
            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(this) ?: run {
                notifyError(sessionId, "Apps Script URL not configured")
                ACTIVE.remove(sessionId)
                return
            }
            val jsonStr = File(jsonPath).readText()
            val root = JSONObject(jsonStr)
            if (root.optString("format") != "blocks_v1") {
                notifyError(sessionId, "Unsupported format for rich send")
                ACTIVE.remove(sessionId)
                return
            }
            val blocks = root.getJSONArray("blocks")
            runCatching {
                var img=0; var text=0; var list=0; var head=0; var other=0
                for (i in 0 until blocks.length()) {
                    when (blocks.getJSONObject(i).optString("type")) {
                        "image" -> img++
                        "p" -> text++
                        "list_item" -> list++
                        "h1","h2","h3","h4","h5","h6" -> head++
                        else -> other++
                    }
                }
                Log.d(TAG, "Session $sessionId: blocks summary total=${blocks.length()} images=$img text=$text list=$list headings=$head other=$other")
            }
            // Build chunks aiming for ~64KB per payload
            val chunks = mutableListOf<JSONArray>()
            var current = JSONArray()
            var size = 0
            for (i in 0 until blocks.length()) {
                val item = blocks.getJSONObject(i)
                val s = item.toString().length
                if (size + s > 64_000 && current.length() > 0) {
                    Log.d(TAG, "Chunk ${chunks.size + 1}: approxBytes=$size items=${current.length()}")
                    chunks.add(current)
                    current = JSONArray()
                    size = 0
                }
                current.put(item)
                size += s
            }
            if (current.length() > 0) {
                Log.d(TAG, "Chunk ${chunks.size + 1}: approxBytes=$size items=${current.length()}")
                chunks.add(current)
            }

            val total = chunks.size
            saveTotal(sessionId, total)
            saveStatus(sessionId, "uploading")
            var index = 0
            if (resume) {
                index = getSavedIndex(sessionId)
            }
            while (index < total) {
                if (ACTIVE[sessionId]?.cancelled == true) {
                    saveStatus(sessionId, "cancelled")
                    notifyError(sessionId, "Send cancelled")
                    ACTIVE.remove(sessionId)
                    return
                }
                updateNotification(sessionId, index + 1, total)
                runCatching {
                    val arr = chunks[index]
                    var imgs=0; var non=0
                    for (i in 0 until arr.length()) {
                        if (arr.getJSONObject(i).optString("type") == "image") imgs++ else non++
                    }
                    Log.d(TAG, "Sending chunk ${index + 1}/$total: items=${arr.length()} images=$imgs others=$non")
                }
                val payload = JSONObject()
                payload.put("mode", "blocks_v1")
                payload.put("sessionId", sessionId)
                payload.put("chunkIndex", index)
                payload.put("totalChunks", total)
                payload.put("blocks", chunks[index])
                // Include stats placement flags for server-side insertion control
                val stTop = SharedPreferencesHelper.loadStatsTop(this@RichSendService)
                val stBottom = SharedPreferencesHelper.loadStatsBottom(this@RichSendService)
                payload.put("statsTop", stTop)
                payload.put("statsBottom", stBottom)

                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val req = Request.Builder().url(appsScriptUrl).post(body).build()
                Log.d(TAG, "Sending chunk ${index + 1}/$total (bytes=${payload.toString().length})")
                val resp = client.newCall(req).execute()
                val code = resp.code
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Chunk send failed: $code $bodyStr")
                    saveIndex(sessionId, index)
                    saveStatus(sessionId, "error")
                    saveError(sessionId, code, bodyStr)
                    notifyFailure(sessionId, code, bodyStr)
                    ACTIVE.remove(sessionId)
                    return
                }
                // Validate Apps Script response content
                val trimmed = bodyStr.trim()
                var ok = false
                try {
                    if (trimmed.startsWith("{")) {
                        val o = JSONObject(trimmed)
                        val next = o.optInt("nextIndex", -1)
                        ok = (o.optBoolean("ok", false) || next == (index + 1))
                        Log.d(TAG, "Chunk ${index + 1} response ok=${ok} next=${next}")
                    } else if (trimmed.equals("OK", ignoreCase = true)) {
                        ok = true
                        Log.d(TAG, "Chunk ${index + 1} response OK text")
                    } else if (!trimmed.startsWith("ERROR")) {
                        // Some deployments may return HTML/other; be lenient but log
                        Log.w(TAG, "Unexpected response body for chunk ${index + 1}: ${trimmed.take(200)}")
                        ok = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse response JSON: ${e.message}")
                    ok = false
                }
                if (!ok) {
                    Log.e(TAG, "Server indicated failure for chunk ${index + 1}: ${trimmed.take(500)}")
                    saveIndex(sessionId, index)
                    saveStatus(sessionId, "error")
                    saveError(sessionId, 200, trimmed.ifBlank { "Unknown server error" })
                    notifyFailure(sessionId, 200, trimmed.ifBlank { "Unknown server error" })
                    ACTIVE.remove(sessionId)
                    return
                }
                index++
                saveIndex(sessionId, index)
            }
            // success
            clearIndex(sessionId)
            saveStatus(sessionId, "done")
            updateNotification(sessionId, total, total, done = true)
            ACTIVE.remove(sessionId)

            // Read original HTML to save to history
            try {
                val html = extractHtmlFromBlocksFile(jsonPath)
                val attachments = extractAttachmentsFromSession(sessionId)
                SharedPreferencesHelper.markHistoryItemSent(this, sessionId, html, attachments)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to save rich message to history: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Session error", t)
            val msg = ((t::class.java.simpleName ?: "Error") + ": " + (t.message ?: "Unknown error")).trim()
            saveStatus(sessionId, "error")
            saveError(sessionId, -1, msg)
            notifyFailure(sessionId, -1, msg)
            ACTIVE.remove(sessionId)
        }
    }

    private fun extractHtmlFromBlocksFile(path: String): String {
        // Original HTML isn't in blocks file; store alongside with .html if present
        val htmlFile = File(path).resolveSibling(File(path).nameWithoutExtension + ".html")
        return if (htmlFile.exists()) htmlFile.readText() else ""
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Sending", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Sending content"
            ch.enableLights(false)
            ch.enableVibration(false)
            ch.lightColor = Color.BLUE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    private fun startForegroundIfNeeded(sessionId: String) {
        val notif = buildNotification(sessionId, 0, 0, false, false).build()
        startForeground(FOREGROUND_ID, notif)
    }

    private fun updateNotification(sessionId: String, current: Int, total: Int, done: Boolean = false, cancelled: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId(sessionId), buildNotification(sessionId, current, total, done, cancelled).build())
    }

    private fun buildNotification(sessionId: String, current: Int, total: Int, done: Boolean, cancelled: Boolean): NotificationCompat.Builder {
        val title = when {
            cancelled -> "Send cancelled"
            done -> "Send complete"
            total > 0 -> "Sending ${current}/${total}"
            else -> "Preparing to send"
        }
        val cancelIntent = Intent(this, RichSendService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val cancelPI = PendingIntent.getService(this, sessionId.hashCode(), cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(!done && !cancelled)
            .setProgress(if (total>0) total else 0, if (total>0) current else 0, total==0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPI)

        if (done || cancelled) {
            builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
            builder.setOngoing(false)
            builder.setProgress(0,0,false)
        }
        return builder
    }

    private fun notifyError(sessionId: String, msg: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Send error")
            .setContentText(msg)
        nm.notify(notificationId(sessionId), b.build())
    }

    private fun notifyFailure(sessionId: String, code: Int, body: String) {
        val resumeIntent = Intent(this, RichSendService::class.java).apply {
            action = ACTION_RESUME
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_JSON_PATH, getSavedJsonPath(sessionId))
        }
        val resumePI = PendingIntent.getService(this, sessionId.hashCode()+1, resumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val tryAgainIntent = Intent(this, RichSendService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_JSON_PATH, getSavedJsonPath(sessionId))
        }
        val tryAgainPI = PendingIntent.getService(this, sessionId.hashCode()+2, tryAgainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Send failed ($code)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .addAction(android.R.drawable.ic_menu_rotate, "Resume", resumePI)
            .addAction(android.R.drawable.ic_menu_revert, "Try Again", tryAgainPI)
        nm.notify(notificationId(sessionId), b.build())
    }

    private fun notificationId(sessionId: String) = 2000 + (sessionId.hashCode() and 0x7FFFFFFF) % 1000

    private fun saveIndex(sessionId: String, index: Int) {
        getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().putInt("index_$sessionId", index).apply()
    }
    private fun getSavedIndex(sessionId: String): Int = getSharedPreferences("send_sessions", Context.MODE_PRIVATE).getInt("index_$sessionId", 0)
    private fun clearIndex(sessionId: String) { getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().remove("index_$sessionId").apply() }
    private fun getSavedJsonPath(sessionId: String): String = getSharedPreferences("send_sessions", Context.MODE_PRIVATE).getString("json_$sessionId", "") ?: ""
    private fun saveJsonPath(sessionId: String, path: String) { getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().putString("json_$sessionId", path).apply() }
    private fun saveTotal(sessionId: String, total: Int) { getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().putInt("total_$sessionId", total).apply() }
    private fun saveStatus(sessionId: String, status: String) { getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().putString("status_$sessionId", status).apply() }
    private fun saveError(sessionId: String, code: Int, message: String) {
        getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit()
            .putInt("errorCode_$sessionId", code)
            .putString("errorMessage_$sessionId", message)
            .apply()
        try {
            SharedPreferencesHelper.markHistoryItemFailed(this, sessionId, "$code: $message")
        } catch (_: Exception) {}
    }

    private fun extractAttachmentsFromSession(sessionId: String): List<com.vibecode.notestasher.HistoryAttachment> {
        return try {
            val prefs = getSharedPreferences("send_sessions", Context.MODE_PRIVATE)
            val ajson = prefs.getString("attachments_$sessionId", null) ?: return emptyList()
            val arr = org.json.JSONArray(ajson)
            val list = mutableListOf<com.vibecode.notestasher.HistoryAttachment>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    com.vibecode.notestasher.HistoryAttachment(
                        thumbDataUrl = o.optString("thumbDataUrl", o.optString("dataUrl")),
                        dataUrl = o.optString("dataUrl", null),
                        mime = o.optString("mime", null)
                    )
                )
            }
            list
        } catch (t: Throwable) { emptyList<com.vibecode.notestasher.HistoryAttachment>() }
    }

    data class SessionState(var cancelled: Boolean = false)

    companion object {
        private const val TAG = "RichSendService"
        private const val CHANNEL_ID = "send_progress"
        private const val FOREGROUND_ID = 42
        private const val MAX_CONCURRENT = 2
        private val ACTIVE = ConcurrentHashMap<String, SessionState>()

        const val ACTION_START = "com.vibecode.notestasher.RichSendService.START"
        const val ACTION_CANCEL = "com.vibecode.notestasher.RichSendService.CANCEL"
        const val ACTION_RESUME = "com.vibecode.notestasher.RichSendService.RESUME"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_JSON_PATH = "jsonPath"

        fun start(context: Context, sessionId: String, jsonPath: String) {
            // Persist path for resume
            context.getSharedPreferences("send_sessions", Context.MODE_PRIVATE)
                .edit().putString("json_$sessionId", jsonPath).apply()
            val i = Intent(context, RichSendService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_JSON_PATH, jsonPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
