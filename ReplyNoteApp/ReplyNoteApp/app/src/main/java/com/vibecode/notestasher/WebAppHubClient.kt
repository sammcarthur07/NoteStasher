package com.vibecode.notestasher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebAppHubClient {
    private const val TAG = "WebAppHubClient"
    const val CONFIG_URL = "https://script.google.com/macros/s/AKfycbybtpY6MlIg6pN8pMt8bNo9zwfRk4FciM6vAAdreb-4V20RweNQ6giIzPFXjrK5fhgOVQ/exec"
    
    // Cached hub URL and config
    private var cachedHubUrl: String? = null
    private var cachedMasterPassword: String? = null
    private var lastConfigFetch: Long = 0
    private const val CONFIG_CACHE_DURATION = 5 * 60 * 1000L // 5 minutes
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    // Fetch the current configuration from the config server with redirect support
    suspend fun fetchConfig(context: Context): Result<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()
            
            // Return cached config if still valid
            if (cachedHubUrl != null && cachedMasterPassword != null && 
                (currentTime - lastConfigFetch) < CONFIG_CACHE_DURATION) {
                return@withContext Result.success(Triple(cachedHubUrl!!, cachedMasterPassword!!, 
                    SharedPreferencesHelper.loadConfigUrl(context) ?: CONFIG_URL))
            }
            
            // Start with either user-configured URL or hardcoded default
            var configUrl = SharedPreferencesHelper.loadConfigUrl(context) ?: CONFIG_URL
            val visitedUrls = mutableSetOf<String>()
            var hubUrl: String? = null
            var masterPassword: String? = null
            
            // Follow redirect chain with loop detection
            while (visitedUrls.size < 10) { // Max 10 redirects to prevent infinite loops
                if (visitedUrls.contains(configUrl)) {
                    Log.w(TAG, "Config redirect loop detected at $configUrl")
                    break
                }
                visitedUrls.add(configUrl)
                
                val url = configUrl.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("action", "getConfig")
                    .build()
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    hubUrl = json.getString("hubUrl")
                    masterPassword = json.getString("masterPassword")
                    val nextConfigUrl = json.optString("nextConfigUrl", configUrl)
                    
                    if (nextConfigUrl != configUrl) {
                        // Follow redirect
                        Log.d(TAG, "Config redirect: $configUrl -> $nextConfigUrl")
                        configUrl = nextConfigUrl
                        continue
                    } else {
                        // No redirect, this is the final config
                        break
                    }
                } else {
                    Log.e(TAG, "Failed to fetch config from $configUrl: $responseBody")
                    break
                }
            }
            
            if (hubUrl != null && masterPassword != null) {
                // Update cache
                cachedHubUrl = hubUrl
                cachedMasterPassword = masterPassword
                lastConfigFetch = currentTime
                
                // Save to SharedPreferences as fallback
                SharedPreferencesHelper.saveHubUrl(context, hubUrl)
                SharedPreferencesHelper.saveMasterPassword(context, masterPassword)
                SharedPreferencesHelper.saveConfigUrl(context, configUrl)
                
                Log.d(TAG, "Config fetched successfully: hubUrl=$hubUrl, configUrl=$configUrl")
                Result.success(Triple(hubUrl, masterPassword, configUrl))
            } else {
                // Fall back to SharedPreferences
                val fallbackUrl = SharedPreferencesHelper.loadHubUrl(context) 
                    ?: "https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec"
                val fallbackPassword = SharedPreferencesHelper.loadMasterPassword(context) ?: "sam03"
                val fallbackConfig = SharedPreferencesHelper.loadConfigUrl(context) ?: CONFIG_URL
                Result.success(Triple(fallbackUrl, fallbackPassword, fallbackConfig))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching config", e)
            // Fall back to SharedPreferences on network error
            val fallbackUrl = SharedPreferencesHelper.loadHubUrl(context) 
                ?: "https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec"
            val fallbackPassword = SharedPreferencesHelper.loadMasterPassword(context) ?: "sam03"
            val fallbackConfig = SharedPreferencesHelper.loadConfigUrl(context) ?: CONFIG_URL
            Result.success(Triple(fallbackUrl, fallbackPassword, fallbackConfig))
        }
    }
    
    // Update the configuration on the config server
    suspend fun updateConfig(context: Context, hubUrl: String? = null, masterPassword: String? = null, nextConfigUrl: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Get current password for authentication
            val currentPassword = cachedMasterPassword ?: SharedPreferencesHelper.loadMasterPassword(context) ?: "sam03"
            
            val json = JSONObject().apply {
                hubUrl?.let { put("hubUrl", it) }
                masterPassword?.let { put("masterPassword", it) }
                nextConfigUrl?.let { put("nextConfigUrl", it) }
            }
            
            // Use current config URL from preferences or default
            val configUrl = SharedPreferencesHelper.loadConfigUrl(context) ?: CONFIG_URL
            val url = configUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("action", "setConfig")
                .addQueryParameter("password", currentPassword)
                .build()
            
            val body = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                // Update cache
                hubUrl?.let { cachedHubUrl = it }
                masterPassword?.let { cachedMasterPassword = it }
                lastConfigFetch = System.currentTimeMillis()
                
                // Update SharedPreferences
                hubUrl?.let { SharedPreferencesHelper.saveHubUrl(context, it) }
                masterPassword?.let { SharedPreferencesHelper.saveMasterPassword(context, it) }
                nextConfigUrl?.let { 
                    if (it != configUrl) {
                        // If changing config URL, save locally for next use
                        SharedPreferencesHelper.saveConfigUrl(context, it)
                    }
                }
                
                Log.d(TAG, "Config updated successfully")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Failed to update config: $responseBody")
                Result.failure(Exception("Failed to update config: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config", e)
            Result.failure(e)
        }
    }
    
    // Get the current hub URL (fetches if needed)
    private suspend fun getHubUrl(context: Context): String {
        val configResult = fetchConfig(context)
        return if (configResult.isSuccess) {
            configResult.getOrNull()?.first ?: SharedPreferencesHelper.loadHubUrl(context) 
                ?: "https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec"
        } else {
            SharedPreferencesHelper.loadHubUrl(context) 
                ?: "https://script.google.com/macros/s/AKfycbwfxRw3tfTi_qOtIGIPPREieLnB1KpT7GDXIaIefJfincg89kb-H8kTIbPRXfCoGyRKDg/exec"
        }
    }
    
    suspend fun registerDoc(
        context: Context,
        docId: String,
        config: DocConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = SharedPreferencesHelper.loadHubUserToken(context)
            val hubUrl = getHubUrl(context)
            
            val url = hubUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("docId", docId)
                .addQueryParameter("action", "registerDoc")
                .addQueryParameter("statsTop", config.statsTop.toString())
                .addQueryParameter("statsBottom", config.statsBottom.toString())
                .addQueryParameter("timezone", config.timezone ?: java.util.TimeZone.getDefault().id)
                .addQueryParameter("autoEnabled", (config.autoEnabled ?: false).toString())
                .addQueryParameter("minutes", (config.minutes ?: 5).toString())
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody())
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (response.isSuccessful) {
                Log.d(TAG, "Document registered successfully: $docId")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Failed to register document: $responseBody")
                Result.failure(Exception("Failed to register: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering document", e)
            Result.failure(e)
        }
    }
    
    suspend fun setConfig(
        context: Context,
        docId: String,
        config: DocConfig
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Use Hub URL for stats config (same as messaging)
            val token = SharedPreferencesHelper.loadHubUserToken(context)
            val hubUrl = getHubUrl(context)
            Log.d(TAG, "=== STATS CONFIG START (HUB) ===")
            Log.d(TAG, "Hub URL: $hubUrl")
            Log.d(TAG, "DocId: $docId")
            Log.d(TAG, "Config - statsTop: ${config.statsTop}, statsBottom: ${config.statsBottom}")
            Log.d(TAG, "Hub token: ${if (token.isNullOrBlank()) "MISSING" else "present (${token.length} chars)"}")
            
            val urlBuilder = hubUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("docId", docId)
                .addQueryParameter("action", "setConfig")
            
            config.statsTop?.let { 
                urlBuilder.addQueryParameter("statsTop", it.toString())
                Log.d(TAG, "Added statsTop parameter: $it")
            }
            config.statsBottom?.let { 
                urlBuilder.addQueryParameter("statsBottom", it.toString()) 
                Log.d(TAG, "Added statsBottom parameter: $it")
            }
            config.statsAnywhere?.let { 
                urlBuilder.addQueryParameter("statsAnywhere", it.toString())
                Log.d(TAG, "Added statsAnywhere parameter: $it")
            }
            config.timezone?.let { 
                urlBuilder.addQueryParameter("timezone", it)
                Log.d(TAG, "Added timezone parameter: $it")
            }
            config.autoEnabled?.let { 
                urlBuilder.addQueryParameter("autoEnabled", it.toString())
                Log.d(TAG, "Added autoEnabled parameter: $it")
            }
            config.minutes?.let { 
                urlBuilder.addQueryParameter("minutes", it.toString())
                Log.d(TAG, "Added minutes parameter: $it")
            }
            
            val finalUrl = urlBuilder.build()
            Log.d(TAG, "Final Hub request URL: $finalUrl")
            
            val request = Request.Builder()
                .url(finalUrl)
                .post("".toRequestBody())
                .build()
            
            Log.d(TAG, "Sending POST request to Hub...")
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            Log.d(TAG, "Hub response code: ${response.code}")
            Log.d(TAG, "Hub response message: ${response.message}")
            Log.d(TAG, "Hub response body: $responseBody")
            Log.d(TAG, "=== STATS CONFIG END (HUB) ===")
            
            if (response.isSuccessful) {
                Log.d(TAG, "Config updated successfully via Hub for doc: $docId")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Failed to update config via Hub - HTTP ${response.code}: $responseBody")
                Result.failure(Exception("Hub HTTP ${response.code}: $responseBody"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating config via Hub", e)
            Log.e(TAG, "Exception details: ${e.message}")
            Log.e(TAG, "=== STATS CONFIG HUB ERROR END ===")
            Result.failure(e)
        }
    }
    
    suspend fun appendPlain(
        context: Context,
        docId: String,
        text: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = SharedPreferencesHelper.loadHubUserToken(context)
            
            val hubUrl = getHubUrl(context)
            val url = hubUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("docId", docId)
                .addQueryParameter("action", "append")
                .build()
            
            val body = text.toRequestBody("text/plain".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Plain text appended successfully")
                Result.success(Unit)
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to append plain text: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending plain text", e)
            Result.failure(e)
        }
    }
    
    suspend fun appendBlocks(
        context: Context,
        docId: String,
        blocksJson: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = SharedPreferencesHelper.loadHubUserToken(context)
            
            val hubUrl = getHubUrl(context)
            val url = hubUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("docId", docId)
                .addQueryParameter("action", "append")
                .build()
            
            val body = blocksJson.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Blocks appended successfully")
                Result.success(Unit)
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to append blocks: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error appending blocks", e)
            Result.failure(e)
        }
    }
    
    suspend fun updateStats(
        context: Context,
        docId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = SharedPreferencesHelper.loadHubUserToken(context)
            
            val hubUrl = getHubUrl(context)
            val url = hubUrl.toHttpUrl()
                .newBuilder()
                .addQueryParameter("token", token)
                .addQueryParameter("docId", docId)
                .addQueryParameter("action", "updateStats")
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post("".toRequestBody())
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "Stats updated successfully")
                Result.success(Unit)
            } else {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Failed to update stats: $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stats", e)
            Result.failure(e)
        }
    }
    
    suspend fun testConnection(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Simple GET request to check if the URL responds
            val hubUrl = getHubUrl(context)
            val request = Request.Builder()
                .url(hubUrl)
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            // Check if it's likely a valid Apps Script Web App
            if (response.isSuccessful || body.contains("error") || body.contains("Invalid")) {
                Result.success(true)
            } else {
                Result.failure(Exception("Invalid response from hub URL"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test connection", e)
            Result.failure(e)
        }
    }
    
    suspend fun fetchDocumentTitle(docId: String): String? = withContext(Dispatchers.IO) {
        try {
            // Try to fetch document title via a public API endpoint
            // Note: Google Docs doesn't have a public API for getting titles without auth
            // We could potentially scrape the public preview page, but that's unreliable
            // For now, we'll try to get a basic title from the document's public metadata
            
            val publicUrl = "https://docs.google.com/document/d/$docId/edit"
            val request = Request.Builder()
                .url(publicUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                // Try to extract title from the HTML
                val titleRegex = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                val match = titleRegex.find(html)
                if (match != null) {
                    var title = match.groupValues[1]
                    // Clean up the title (remove " - Google Docs" suffix)
                    title = title.replace(" - Google Docs", "")
                        .replace(" - Google Drive", "")
                        .trim()
                    
                    // If we got a valid title (not empty or error page)
                    if (title.isNotEmpty() && !title.contains("Error") && !title.contains("404")) {
                        Log.d(TAG, "Fetched document title: $title")
                        return@withContext title
                    }
                }
            }
            
            Log.d(TAG, "Could not fetch document title for: $docId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching document title", e)
            null
        }
    }
    
    data class QueuedMessage(
        val docId: String,
        val content: String,
        val isRich: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
}