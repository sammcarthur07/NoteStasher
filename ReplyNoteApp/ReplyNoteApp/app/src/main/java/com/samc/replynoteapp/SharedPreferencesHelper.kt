package com.samc.replynoteapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log // Import for Log.d
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

data class HistoryAttachment(
    val thumbDataUrl: String,
    val dataUrl: String? = null,
    val mime: String? = null
)

data class MessageHistoryItem(
    val text: String = "",
    val timestamp: Long,
    val dateTimeString: String,
    val html: String? = null,
    val format: String? = null, // "plain" or "html"
    val sessionId: String? = null,
    val status: String? = null, // null or "sending" or "failed" or "sent"
    val error: String? = null,
    val attachments: List<HistoryAttachment> = emptyList()
)

object SharedPreferencesHelper {
    private const val PREFS_NAME = "ReplyNoteAppPrefs"
    private const val KEY_APPS_SCRIPT_URL = "apps_script_url"
    private const val KEY_GOOGLE_DOC_URL_OR_ID = "google_doc_url_or_id" // Added for user's Doc URL/ID
    private const val KEY_MESSAGE_HISTORY = "message_history"
    private const val KEY_LAST_TAB_INDEX = "last_tab_index"
    private const val KEY_SELECTED_FONT = "selected_font"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_DRAFT_TEXT = "draft_text"
    private const val KEY_TIMEZONE = "timezone_preference"
    private const val KEY_STATS_TOP = "stats_top"
    private const val KEY_STATS_BOTTOM = "stats_bottom"
    private const val KEY_STATS_ANYWHERE = "stats_anywhere"
    // In-App Script Hub (Option 5) keys
    private const val KEY_USE_IN_APP_SCRIPT = "use_in_app_script"
    private const val KEY_HUB_WEB_APP_URL = "hub_web_app_url"
    private const val KEY_HUB_USER_TOKEN = "hub_user_token"
    private const val KEY_DOC_LIST = "hub_doc_list_json"
    private const val KEY_SELECTED_DOC_ID = "hub_selected_doc_id"
    private val gson = Gson()
    private val dateFormatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveAppsScriptUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_APPS_SCRIPT_URL, url).apply()
        Log.d("PrefsHelper", "Saved Apps Script URL: $url")
    }

    fun loadAppsScriptUrl(context: Context): String? {
        val url = getPrefs(context).getString(KEY_APPS_SCRIPT_URL, null)
        Log.d("PrefsHelper", "Loaded Apps Script URL: $url")
        return url
    }

    // New function to save Google Doc URL/ID
    fun saveGoogleDocUrlOrId(context: Context, urlOrId: String) {
        getPrefs(context).edit().putString(KEY_GOOGLE_DOC_URL_OR_ID, urlOrId).apply()
        Log.d("PrefsHelper", "Saved Google Doc URL/ID: $urlOrId")
    }

    // New function to load Google Doc URL/ID
    fun loadGoogleDocUrlOrId(context: Context): String? {
        val urlOrId = getPrefs(context).getString(KEY_GOOGLE_DOC_URL_OR_ID, null)
        Log.d("PrefsHelper", "Loaded Google Doc URL/ID: $urlOrId")
        return urlOrId
    }

    // Message history functions
    fun saveMessageToHistory(context: Context, message: String) {
        Log.d("PrefsHelper", "=== saveMessageToHistory called ===")
        Log.d("PrefsHelper", "Message to save: '$message' (length: ${message.length})")
        
        val currentHistory = loadMessageHistory(context).toMutableList()
        Log.d("PrefsHelper", "Current history size: ${currentHistory.size}")
        
        val timestamp = System.currentTimeMillis()
        val dateTimeString = dateFormatter.format(Date(timestamp))
        val newItem = MessageHistoryItem(text = message, timestamp = timestamp, dateTimeString = dateTimeString, html = null, format = "plain")
        currentHistory.add(0, newItem) // Add to beginning for newest first
        
        // Keep only last 100 messages to prevent excessive storage
        val trimmedHistory = currentHistory.take(100)
        Log.d("PrefsHelper", "Trimmed history size: ${trimmedHistory.size}")
        
        val json = gson.toJson(trimmedHistory)
        Log.d("PrefsHelper", "JSON length: ${json.length}")
        
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
        Log.d("PrefsHelper", "Message saved to SharedPreferences successfully")
        
        // Verify save
        val verifyHistory = loadMessageHistory(context)
        Log.d("PrefsHelper", "Verification - history size after save: ${verifyHistory.size}")
        if (verifyHistory.isNotEmpty()) {
            Log.d("PrefsHelper", "Verification - latest message: '${verifyHistory[0].text.take(50)}...'")
        }
    }

    fun saveRichMessageToHistory(context: Context, html: String, plainPreview: String = "", attachments: List<HistoryAttachment> = emptyList()) {
        Log.d("PrefsHelper", "=== saveRichMessageToHistory called ===")
        Log.d("PrefsHelper", "HTML length: ${html.length}")

        val currentHistory = loadMessageHistory(context).toMutableList()
        val timestamp = System.currentTimeMillis()
        val dateTimeString = dateFormatter.format(Date(timestamp))
        val snippet = if (plainPreview.isNotBlank()) plainPreview else htmlToPlainSnippet(html, 200)
        val newItem = MessageHistoryItem(text = snippet, timestamp = timestamp, dateTimeString = dateTimeString, html = html, format = "html", status = "sent", attachments = attachments)
        currentHistory.add(0, newItem)

        val trimmedHistory = lightenHistory(currentHistory.take(100))
        val json = gson.toJson(trimmedHistory)
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
        Log.d("PrefsHelper", "Rich message saved to history successfully")
    }

    fun saveSendingRichHistoryItem(context: Context, sessionId: String, htmlPreview: String, plainPreview: String = "", attachments: List<HistoryAttachment> = emptyList()) {
        Log.d("PrefsHelper", "saveSendingRichHistoryItem: sessionId=$sessionId htmlLen=${htmlPreview.length} plainPreviewLen=${plainPreview.length} attCount=${attachments.size}")
        // For debugging: derive a rough text snippet from html to see what would be shown as paragraph preview
        try {
            val debugSnippet = htmlToPlainSnippet(htmlPreview, 200)
            Log.d("PrefsHelper", "Derived snippet from html: '${debugSnippet}'")
        } catch (_: Exception) {}

        val current = loadMessageHistory(context).toMutableList()
        val ts = System.currentTimeMillis()
        val dt = dateFormatter.format(Date(ts))
        val snippet = if (plainPreview.isNotBlank()) plainPreview else htmlToPlainSnippet(htmlPreview, 200)
        val item = MessageHistoryItem(text = snippet, timestamp = ts, dateTimeString = dt, html = htmlPreview, format = "html", sessionId = sessionId, status = "sending", attachments = attachments)
        current.add(0, item)
        val json = gson.toJson(lightenHistory(current).take(100))
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
    }

    fun markHistoryItemSent(context: Context, sessionId: String, html: String, attachments: List<HistoryAttachment> = emptyList()) {
        Log.d("PrefsHelper", "markHistoryItemSent: sessionId=$sessionId htmlLen=${html.length} attCount=${attachments.size}")
        val list = loadMessageHistory(context).toMutableList()
        for (i in list.indices) {
            val it = list[i]
            if (it.sessionId == sessionId) {
                val snippet = if (it.text.isNotBlank()) it.text else htmlToPlainSnippet(html, 200)
                list[i] = it.copy(text = snippet, html = html, status = "sent", attachments = attachments)
                break
            }
        }
        val json = gson.toJson(lightenHistory(list))
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
    }

    fun markHistoryItemFailed(context: Context, sessionId: String, error: String) {
        val list = loadMessageHistory(context).toMutableList()
        for (i in list.indices) {
            val it = list[i]
            if (it.sessionId == sessionId) {
                // Drop heavy attachment payloads on failure to avoid OOM; keep thumbs only
                val lightAtts = it.attachments.map { a -> HistoryAttachment(thumbDataUrl = a.thumbDataUrl) }
                list[i] = it.copy(status = "failed", error = error, attachments = lightAtts)
                break
            }
        }
        val json = gson.toJson(lightenHistory(list))
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
    }

    fun loadMessageHistory(context: Context): List<MessageHistoryItem> {
        Log.d("PrefsHelper", "=== loadMessageHistory called ===")
        val json = getPrefs(context).getString(KEY_MESSAGE_HISTORY, "[]")
        Log.d("PrefsHelper", "Loaded JSON from prefs (length: ${json?.length ?: 0})")
        
        return try {
            val type = object : TypeToken<List<MessageHistoryItem>>() {}.type
            val history: List<MessageHistoryItem> = gson.fromJson(json, type) ?: emptyList()
            Log.d("PrefsHelper", "Successfully parsed ${history.size} messages from history")
            if (history.isNotEmpty()) {
                Log.d("PrefsHelper", "Latest message: '${history[0].text.take(50)}...' at ${history[0].dateTimeString}")
            }
            history
        } catch (e: Exception) {
            Log.e("PrefsHelper", "Error loading message history: ${e.message}", e)
            emptyList()
        }
    }
    
    fun updateMessageHistoryStatus(context: Context, index: Int, status: String) {
        val history = loadMessageHistory(context).toMutableList()
        if (index >= 0 && index < history.size) {
            history[index] = history[index].copy(status = status)
            val json = gson.toJson(history)
            getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, json).apply()
        }
    }

    fun clearMessageHistory(context: Context) {
        getPrefs(context).edit().putString(KEY_MESSAGE_HISTORY, "[]").apply()
        Log.d("PrefsHelper", "Cleared message history")
    }

    // Ensure we don't persist massive base64 payloads in history. Keep only thumbnails.
    private fun lightenHistory(list: List<MessageHistoryItem>, maxItems: Int = 100): List<MessageHistoryItem> {
        val out = ArrayList<MessageHistoryItem>(minOf(list.size, maxItems))
        val take = list.take(maxItems)
        for (it in take) {
            val lightAtts = it.attachments.map { a -> HistoryAttachment(thumbDataUrl = a.thumbDataUrl) }
            out.add(it.copy(attachments = lightAtts))
        }
        return out
    }

    // Simple HTML -> text snippet for debugging and previews (images removed)
    private fun htmlToPlainSnippet(html: String, maxLen: Int = 200): String {
        // remove scripts/styles
        var s = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        // remove images
        s = s.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
        // convert some block tags to newlines
        s = s.replace(Regex("</(p|div|h[1-6]|li|br|tr)>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        // strip remaining tags
        s = s.replace(Regex("<[^>]+>"), "")
        // decode a few entities
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return if (s.length > maxLen) s.take(maxLen) + "…" else s
    }
    
    // Tab persistence
    fun saveLastTabIndex(context: Context, tabIndex: Int) {
        getPrefs(context).edit().putInt(KEY_LAST_TAB_INDEX, tabIndex).apply()
        Log.d("PrefsHelper", "Saved last tab index: $tabIndex")
    }
    
    fun loadLastTabIndex(context: Context): Int {
        val index = getPrefs(context).getInt(KEY_LAST_TAB_INDEX, 0)
        Log.d("PrefsHelper", "Loaded last tab index: $index")
        return index
    }
    
    // Font preference persistence
    fun saveSelectedFont(context: Context, fontName: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_FONT, fontName).apply()
        Log.d("PrefsHelper", "Saved selected font: $fontName")
    }
    
    fun loadSelectedFont(context: Context): String {
        val font = getPrefs(context).getString(KEY_SELECTED_FONT, "Inter") ?: "Inter"
        Log.d("PrefsHelper", "Loaded selected font: $font")
        return font
    }
    
    // Font size persistence
    fun saveFontSize(context: Context, size: Int) {
        getPrefs(context).edit().putInt(KEY_FONT_SIZE, size).apply()
        Log.d("PrefsHelper", "Saved font size: $size")
    }
    
    fun loadFontSize(context: Context): Int {
        val size = getPrefs(context).getInt(KEY_FONT_SIZE, 10) // Default to middle (10)
        Log.d("PrefsHelper", "Loaded font size: $size")
        return size
    }
    
    // Draft text persistence
    fun saveDraftText(context: Context, text: String) {
        getPrefs(context).edit().putString(KEY_DRAFT_TEXT, text).apply()
        Log.d("PrefsHelper", "Saved draft text (length: ${text.length})")
    }
    
    fun loadDraftText(context: Context): String {
        val text = getPrefs(context).getString(KEY_DRAFT_TEXT, "") ?: ""
        Log.d("PrefsHelper", "Loaded draft text (length: ${text.length})")
        return text
    }
    
    fun clearDraftText(context: Context) {
        getPrefs(context).edit().putString(KEY_DRAFT_TEXT, "").apply()
        Log.d("PrefsHelper", "Cleared draft text")
    }

    // Stats placement preferences - per document
    fun saveStatsTop(context: Context, enabled: Boolean) {
        val docId = loadSelectedDocId(context) ?: return
        getPrefs(context).edit().putBoolean("${KEY_STATS_TOP}_$docId", enabled).apply()
        Log.d("PrefsHelper", "Saved statsTop for $docId: $enabled")
    }

    fun loadStatsTop(context: Context): Boolean {
        val docId = loadSelectedDocId(context) ?: return false
        val v = getPrefs(context).getBoolean("${KEY_STATS_TOP}_$docId", false)
        Log.d("PrefsHelper", "Loaded statsTop for $docId: $v")
        return v
    }

    fun saveStatsBottom(context: Context, enabled: Boolean) {
        val docId = loadSelectedDocId(context) ?: return
        getPrefs(context).edit().putBoolean("${KEY_STATS_BOTTOM}_$docId", enabled).apply()
        Log.d("PrefsHelper", "Saved statsBottom for $docId: $enabled")
    }

    fun loadStatsBottom(context: Context): Boolean {
        val docId = loadSelectedDocId(context) ?: return true  // Default to true for bottom stats
        val v = getPrefs(context).getBoolean("${KEY_STATS_BOTTOM}_$docId", true)
        Log.d("PrefsHelper", "Loaded statsBottom for $docId: $v")
        return v
    }

    fun saveStatsAnywhere(context: Context, enabled: Boolean) {
        val docId = loadSelectedDocId(context) ?: return
        getPrefs(context).edit().putBoolean("${KEY_STATS_ANYWHERE}_$docId", enabled).apply()
        Log.d("PrefsHelper", "Saved statsAnywhere for $docId: $enabled")
    }

    fun loadStatsAnywhere(context: Context): Boolean {
        val docId = loadSelectedDocId(context) ?: return false
        val v = getPrefs(context).getBoolean("${KEY_STATS_ANYWHERE}_$docId", false)
        Log.d("PrefsHelper", "Loaded statsAnywhere for $docId: $v")
        return v
    }

    // Timezone persistence
    fun saveTimezone(context: Context, tz: String) {
        getPrefs(context).edit().putString(KEY_TIMEZONE, tz).apply()
        Log.d("PrefsHelper", "Saved timezone: $tz")
    }

    fun loadTimezone(context: Context): String {
        val sys = java.util.TimeZone.getDefault().id
        val tz = getPrefs(context).getString(KEY_TIMEZONE, sys) ?: sys
        Log.d("PrefsHelper", "Loaded timezone: $tz")
        return tz
    }

    // Auto-update preferences
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_AUTO_UPDATE_MINUTES = "auto_update_minutes"

    fun saveAutoUpdateEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
        Log.d("PrefsHelper", "Saved autoUpdateEnabled: $enabled")
    }

    fun loadAutoUpdateEnabled(context: Context): Boolean {
        val v = getPrefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, false)
        Log.d("PrefsHelper", "Loaded autoUpdateEnabled: $v")
        return v
    }

    fun saveAutoUpdateMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_AUTO_UPDATE_MINUTES, minutes).apply()
        Log.d("PrefsHelper", "Saved autoUpdateMinutes: $minutes")
    }

    fun loadAutoUpdateMinutes(context: Context): Int {
        val v = getPrefs(context).getInt(KEY_AUTO_UPDATE_MINUTES, 5)
        Log.d("PrefsHelper", "Loaded autoUpdateMinutes: $v")
        return v
    }

    // ---------------- In-App Script Hub (Option 5) ----------------
    fun saveUseInAppScript(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_IN_APP_SCRIPT, enabled).apply()
    }
    fun loadUseInAppScript(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_IN_APP_SCRIPT, false)
    }

    fun saveHubWebAppUrl(context: Context, url: String) {
        getPrefs(context).edit().putString(KEY_HUB_WEB_APP_URL, url).apply()
    }
    fun loadHubWebAppUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_HUB_WEB_APP_URL, null)
    }
    
    fun saveHubUserToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_HUB_USER_TOKEN, token).apply()
    }
    fun loadHubUserToken(context: Context): String {
        var token = getPrefs(context).getString(KEY_HUB_USER_TOKEN, null)
        if (token == null) {
            // Generate a new random token for this user
            token = java.util.UUID.randomUUID().toString()
            saveHubUserToken(context, token)
        }
        return token
    }

    private fun loadDocListJson(context: Context): String? = getPrefs(context).getString(KEY_DOC_LIST, null)
    private fun saveDocListJson(context: Context, json: String) { getPrefs(context).edit().putString(KEY_DOC_LIST, json).apply() }

    fun loadDocEntries(context: Context): MutableList<DocEntry> {
        val json = loadDocListJson(context)
        if (json.isNullOrBlank()) return mutableListOf()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<DocEntry>>(){}.type
            val list: List<DocEntry> = gson.fromJson(json, type)
            list.toMutableList()
        } catch (e: Exception) { mutableListOf() }
    }
    fun saveDocEntries(context: Context, list: List<DocEntry>) {
        val json = gson.toJson(list)
        saveDocListJson(context, json)
    }

    fun addDocEntry(context: Context, entry: DocEntry, maxDocs: Int = 3): Boolean {
        val list = loadDocEntries(context)
        if (list.size >= maxDocs) return false
        if (list.any { it.docId == entry.docId }) return true
        list.add(entry)
        saveDocEntries(context, list)
        return true
    }
    fun removeDocEntry(context: Context, docId: String) {
        val list = loadDocEntries(context)
        val newList = list.filterNot { it.docId == docId }
        saveDocEntries(context, newList)
        val sel = loadSelectedDocId(context)
        if (sel == docId) saveSelectedDocId(context, newList.firstOrNull()?.docId)
    }
    fun updateDocEntry(context: Context, updated: DocEntry) {
        val list = loadDocEntries(context)
        val newList = list.map { if (it.docId == updated.docId) updated else it }
        saveDocEntries(context, newList)
    }

    fun saveSelectedDocId(context: Context, docId: String?) {
        getPrefs(context).edit().putString(KEY_SELECTED_DOC_ID, docId).apply()
    }
    fun loadSelectedDocId(context: Context): String? {
        return getPrefs(context).getString(KEY_SELECTED_DOC_ID, null)
    }
    
    fun saveDocTabState(context: Context, docId: String, tabIndex: Int) {
        getPrefs(context).edit().putInt("doc_tab_$docId", tabIndex).apply()
        Log.d("PrefsHelper", "Saved tab state for doc $docId: $tabIndex")
    }
    
    fun loadDocTabState(context: Context, docId: String): Int {
        val index = getPrefs(context).getInt("doc_tab_$docId", 0) // Default to Setup tab
        Log.d("PrefsHelper", "Loaded tab state for doc $docId: $index")
        return index
    }
}
