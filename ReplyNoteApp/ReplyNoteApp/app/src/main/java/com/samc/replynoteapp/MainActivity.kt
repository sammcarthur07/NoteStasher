package com.samc.replynoteapp // <-- UPDATED PACKAGE

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Context.VIBRATOR_SERVICE
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.fontResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.focus.onFocusChanged
import kotlin.math.abs
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlin.coroutines.resume
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import com.samc.replynoteapp.HistoryAttachment
import com.samc.replynoteapp.R
// Ensure this line matches your project's theme file and new package structure
import com.samc.replynoteapp.ui.theme.ReplyNoteAppTheme // <-- UPDATED PACKAGE FOR THEME

class MainActivity : ComponentActivity() {
    
    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted by user.")
            Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show()
            startNotificationServiceConditionally()
        } else {
            Log.d("MainActivity", "Notification permission denied by user.")
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
        
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        
        enableEdgeToEdge()
        setContent {
            ReplyNoteAppTheme { // Check this theme name if it's different
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                var isFirstLaunch by remember { mutableStateOf(prefs.getBoolean("is_first_launch", true)) }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isFirstLaunch) {
                        WelcomeScreen(
                            onDocumentAdded = { docId, alias ->
                                // Save the document
                                val entry = DocEntry(docId = docId, alias = alias)
                                SharedPreferencesHelper.addDocEntry(this, entry, 3)
                                SharedPreferencesHelper.saveSelectedDocId(this, docId)
                                ensureDocNotification(this, entry)
                                
                                // Register with hub
                                GlobalScope.launch {
                                    WebAppHubClient.registerDoc(
                                        this@MainActivity, 
                                        docId,
                                        DocConfig(statsBottom = true, timezone = java.util.TimeZone.getDefault().id)
                                    )
                                }
                                
                                // Mark as not first launch
                                prefs.edit().putBoolean("is_first_launch", false).apply()
                                isFirstLaunch = false
                            },
                            onSkip = {
                                // Create unsynced doc
                                val entry = DocEntry(docId = "unsynced_1", alias = "Unsynced doc 1")
                                SharedPreferencesHelper.addDocEntry(this, entry, 3)
                                SharedPreferencesHelper.saveSelectedDocId(this, "unsynced_1")
                                
                                // Mark as not first launch
                                prefs.edit().putBoolean("is_first_launch", false).apply()
                                isFirstLaunch = false
                            }
                        )
                    } else {
                        AppWithTabs(
                            onCheckPermissionAndStartService = { checkPermissionAndStartService() },
                            context = this
                        )
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStartService() {
        Log.d("MainActivity", "Button clicked: Checking permissions and attempting to start service...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "Notification permission already granted.")
                    startNotificationServiceConditionally()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.d("MainActivity", "Showing rationale for notification permission.")
                    Toast.makeText(this, "Notification permission is needed for notes.", Toast.LENGTH_LONG).show()
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.d("MainActivity", "Requesting notification permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d("MainActivity", "Notification permission not required (SDK < 33).")
            startNotificationServiceConditionally()
        }
    }

    private fun startNotificationServiceConditionally() {
        // Check if any documents have notifications enabled
        val docs = SharedPreferencesHelper.loadDocEntries(this)
        val enabledDocs = docs.filter { doc ->
            SharedPreferencesHelper.loadDocNotificationEnabled(this, doc.docId)
        }
        
        if (enabledDocs.isEmpty()) {
            Toast.makeText(this, "No documents selected for notifications. Please check at least one document.", Toast.LENGTH_LONG).show()
            Log.w("MainActivity", "No documents have notifications enabled.")
            return
        }
        val hasSyncedDoc = enabledDocs.any { !it.docId.startsWith("unsynced") }
        if (!hasSyncedDoc) {
            Toast.makeText(this, "Please sync at least one document before starting notifications.", Toast.LENGTH_LONG).show()
            Log.w("MainActivity", "Notification start blocked - only unsynced docs enabled.")
            return
        }

        startActualNotificationService()
    }

    private fun startActualNotificationService() {
        val intent = Intent(this, NotificationService::class.java)
        try {
            Log.d("MainActivity", ">>> INTENT CREATED for NotificationService <<<")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("MainActivity", ">>> Calling startForegroundService... <<<")
                ContextCompat.startForegroundService(this, intent)
                Log.d("MainActivity", ">>> Called startForegroundService <<<")
            } else {
                Log.d("MainActivity", ">>> Calling startService... <<<")
                startService(intent)
                Log.d("MainActivity", ">>> Called startService <<<")
            }
            Toast.makeText(this, "Notification Service Start Requested", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "Error starting service", e)
        }

    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocTabsBar(
    docs: List<DocEntry>,
    selectedDocId: String?,
    onSelectDoc: (String) -> Unit,
    onAddDoc: () -> Unit,
    onRemoveDoc: (String) -> Unit,
    onRenameDoc: (String) -> Unit
) {
    if (docs.isEmpty()) {
        // Show add button when no docs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onAddDoc) {
                Icon(Icons.Default.Add, contentDescription = "Add Document")
                Spacer(Modifier.width(4.dp))
                Text("Add Document")
            }
        }
    } else {
        ScrollableTabRow(
            selectedTabIndex = docs.indexOfFirst { it.docId == selectedDocId }.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 0.dp
        ) {
            docs.forEach { doc ->
                Tab(
                    selected = doc.docId == selectedDocId,
                    onClick = { onSelectDoc(doc.docId) },
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelectDoc(doc.docId) },
                        onLongClick = { onRenameDoc(doc.docId) }
                    ),
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            val displayName = doc.alias.ifBlank { doc.title }.ifBlank { "…${doc.docId.takeLast(6)}" }
                            Text(displayName, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { onRemoveDoc(doc.docId) },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
            }
            // Add new doc tab
            if (docs.size < 3) {
                Tab(
                    selected = false,
                    onClick = onAddDoc,
                    text = {
                        Icon(Icons.Default.Add, contentDescription = "Add Document")
                    }
                )
            }
        }
    }
}

@Composable
fun RemoveDocDialog(
    docId: String,
    docName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Document") },
        text = { Text("Are you sure you want to remove \"$docName\" from the app?") },
        confirmButton = {
            TextButton(onClick = onConfirm) { 
                Text("Remove", color = MaterialTheme.colorScheme.error) 
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun RenameDocDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Rename Document") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Document Name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onConfirm(newName)
                    }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newName) }) { 
                Text("Save") 
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AddDocumentDialog(
    existingDocs: List<DocEntry>,
    onConfirm: (docId: String, alias: String) -> Unit,
    onDelete: (docId: String) -> Unit,
    onUpdate: (oldDocId: String, newDocId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var urlInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Manage Documents") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Add new document section
                Text("Add New Document", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Google Doc URL or ID") },
                    placeholder = { Text("Leave empty for offline-only doc") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Existing documents section
                if (existingDocs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    Text("Existing Documents", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    
                    existingDocs.forEach { doc ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        doc.alias,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    if (doc.docId.startsWith("unsynced")) {
                                        Text(
                                            "Not synced to Google Docs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else {
                                        Text(
                                            "Doc ID: ${doc.docId.take(20)}...",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                
                                Row {
                                    // Update button for unsynced docs
                                    if (doc.docId.startsWith("unsynced")) {
                                        IconButton(onClick = {
                                            // Pre-fill the URL input for updating
                                            urlInput = ""
                                            aliasInput = doc.alias
                                            Toast.makeText(context, "Enter Google Doc URL above to sync", Toast.LENGTH_LONG).show()
                                        }) {
                                            Icon(Icons.Filled.Refresh, "Sync")
                                        }
                                    }
                                    
                                    // Delete button
                                    IconButton(onClick = { 
                                        showDeleteConfirmation = doc.docId 
                                    }) {
                                        Icon(Icons.Default.Delete, "Delete")
                                    }
                                }
                            }
                        }
                        
                        // Delete confirmation
                        if (showDeleteConfirmation == doc.docId) {
                            AlertDialog(
                                onDismissRequest = { showDeleteConfirmation = null },
                                title = { Text("Delete Document?") },
                                text = { Text("Remove '${doc.alias}' from the app?") },
                                confirmButton = {
                                    TextButton(onClick = {
                                        onDelete(doc.docId)
                                        showDeleteConfirmation = null
                                    }) { Text("Delete") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteConfirmation = null }) { 
                                        Text("Cancel") 
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Add button - adds document and clears fields for next
                TextButton(
                    onClick = {
                        when {
                            urlInput.isNotEmpty() -> {
                                val docId = extractDocIdFromUrl(urlInput)
                                if (docId != null) {
                                    scope.launch {
                                        var finalName = aliasInput
                                        if (finalName.isEmpty()) {
                                            val fetchedTitle = WebAppHubClient.fetchDocumentTitle(docId)
                                            finalName = fetchedTitle ?: "My Document"
                                        }
                                        
                                        withContext(Dispatchers.Main) {
                                            val unsyncedDoc = existingDocs.find { 
                                                it.docId.startsWith("unsynced")
                                            }
                                            
                                            if (unsyncedDoc != null) {
                                                onUpdate(unsyncedDoc.docId, docId)
                                                WebAppHubClient.registerDoc(
                                                    context,
                                                    docId,
                                                    DocConfig(statsBottom = true, timezone = java.util.TimeZone.getDefault().id)
                                                )
                                                MessageQueueManager.syncAllForDoc(context, docId)
                                            } else {
                                                onConfirm(docId, finalName)
                                            }
                                            // Clear fields for next entry
                                            urlInput = ""
                                            aliasInput = ""
                                            Toast.makeText(context, "Document added", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, "Invalid Doc URL/ID", Toast.LENGTH_SHORT).show()
                                }
                            }
                            aliasInput.isNotEmpty() -> {
                                val unsyncedId = "unsynced_${System.currentTimeMillis()}"
                                onConfirm(unsyncedId, aliasInput)
                                urlInput = ""
                                aliasInput = ""
                                Toast.makeText(context, "Offline document added", Toast.LENGTH_SHORT).show()
                            }
                            else -> {
                                Toast.makeText(context, "Enter a name or URL", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = existingDocs.size < 3
                ) { 
                    Text("Add") 
                }
                
                // Done button - saves current entry if any and closes
                TextButton(
                    onClick = {
                        // Save current entry if fields are filled
                        when {
                            urlInput.isNotEmpty() -> {
                                val docId = extractDocIdFromUrl(urlInput)
                                if (docId != null) {
                                    scope.launch {
                                        var finalName = aliasInput
                                        if (finalName.isEmpty()) {
                                            val fetchedTitle = WebAppHubClient.fetchDocumentTitle(docId)
                                            finalName = fetchedTitle ?: "My Document"
                                        }
                                        
                                        withContext(Dispatchers.Main) {
                                            onConfirm(docId, finalName)
                                            onDismiss()
                                        }
                                    }
                                } else {
                                    onDismiss()
                                }
                            }
                            aliasInput.isNotEmpty() -> {
                                val unsyncedId = "unsynced_${System.currentTimeMillis()}"
                                onConfirm(unsyncedId, aliasInput)
                                onDismiss()
                            }
                            else -> {
                                onDismiss()
                            }
                        }
                    }
                ) { 
                    Text("Done") 
                }
            }
        },
        dismissButton = null
    )
}

// Helper to send current content depending on format
private suspend fun sendCurrent(
    context: Context,
    selectedFont: String,
    messageText: String,
    richWebView: android.webkit.WebView?,
    draftRepo: com.samc.replynoteapp.data.DraftRepository,
    onPlainSuccess: () -> Unit = {},
    onBackgroundStarted: (sessionId: String) -> Unit = {}
) {
    if (selectedFont == "Original Format") {
        Log.d("SendCurrent", "Preparing rich send (Original Format)")
        if (richWebView == null) {
            Toast.makeText(context, "Editor not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // --- FIX: The logic has been restructured here ---

        // 1. Always extract the HTML and JSON first.
        fun unescapeJsString(s: String?): String {
            if (s == null) return ""
            var r = s
            if (r.startsWith("\"") && r.endsWith("\"")) {
                r = r.substring(1, r.length-1)
            }
            return r
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        val blocksJson = suspendCancellableCoroutine<String> { cont ->
            richWebView.evaluateJavascript("window.__extractBlocksJSON()") { res ->
                cont.resume(unescapeJsString(res)) {}
            }
        }
        val html = suspendCancellableCoroutine<String> { cont ->
            richWebView.evaluateJavascript("window.__getHtml()") { res ->
                cont.resume(unescapeJsString(res)) {}
            }
        }
        Log.d("SendCurrent", "Extracted html length=${html.length}, blocksJson length=${blocksJson.length}")

        // 2. Now, decide which sending method to use based on the configuration.
        val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context) ?: "unsynced_1"
        val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
        val isOnline = NetworkUtils.isNetworkAvailable(context)

        // Path A: Use hub mode to send directly to the selected document
        // Send HTML as plain text since hub endpoint doesn't support rich images
        Log.d("SendCurrent", "Checking hub mode: docId='$selectedDocId' (len=${selectedDocId.length}), online=$isOnline, appsScriptUrl=${if(appsScriptUrl.isNullOrBlank()) "EMPTY" else "SET"}")
        if (selectedDocId.length > 10 && isOnline && !selectedDocId.startsWith("unsynced")) {
            Log.d("SendCurrent", "Using hub mode for Original Format (sending HTML to selected doc)")
            
            // Check if there are images - hub endpoint doesn't support them
            val ajson = draftRepo.getDraftAttachmentsJson()
            val hasImages = ajson.isNotBlank() && JSONArray(ajson).length() > 0
            
            if (hasImages) {
                // Images present - warn user that images won't be sent via hub
                Toast.makeText(
                    context, 
                    "Note: Images cannot be sent to multiple docs. Use Apps Script for image support.", 
                    Toast.LENGTH_LONG
                ).show()
            }
            
            // Build HTML without images (hub limitation)
            val finalHtml = "-\n\n$html\n"
            
            // Send HTML as plain text to the document
            val result = WebAppHubClient.appendPlain(context, selectedDocId, finalHtml)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(context, "Message sent successfully!", Toast.LENGTH_SHORT).show()
                    // Clear editor content
                    richWebView.evaluateJavascript("window.__setContent(\"\")", null)
                    // Clear attachments from draft
                    draftRepo.clearDraft()
                    draftRepo.saveDraftAttachmentsJson("[]")
                    onPlainSuccess()
                    // Save to history
                    SharedPreferencesHelper.saveMessageToHistory(context, html)
                    val preview = htmlToPlainSnippetLocal(html, 500).ifBlank { messageText.take(500) }
                    SharedPreferencesHelper.saveDocLastMessage(context, selectedDocId, preview)
                    refreshDocNotificationIfEnabled(context, selectedDocId)
                }.onFailure { error ->
                    Toast.makeText(context, "Failed to send: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            return // Exit the function here for this path.
        }

        // Path B: Continue with the original RichSendService / Apps Script logic.
        try {
            val rootDbg = JSONObject(blocksJson)
            val arrDbg = rootDbg.optJSONArray("blocks") ?: JSONArray()
            var img=0; var para=0; var head=0; var list=0; var other=0
            for (i in 0 until arrDbg.length()) {
                val t = arrDbg.optJSONObject(i)?.optString("type") ?: ""
                when (t) {
                    "image" -> img++
                    "p" -> para++
                    "list_item" -> list++
                    "h1","h2","h3","h4","h5","h6" -> head++
                    else -> other++
                }
            }
            Log.d("SendCurrent", "Blocks summary: total=${arrDbg.length()} images=${img} paras=${para} headings=${head} listItems=${list} other=${other}")
        } catch (_: Exception) {}

        val mergedBlocks = try {
            val root = JSONObject(blocksJson.ifBlank { "{\"format\":\"blocks_v1\",\"blocks\":[]}" })
            val arr = root.optJSONArray("blocks") ?: JSONArray()
            val resultArr = JSONArray()
            run {
                val dashBlock = JSONObject().put("type", "p").put("text", "-")
                resultArr.put(dashBlock)
            }
            run {
                val blankBlock = JSONObject().put("type", "p").put("text", "")
                resultArr.put(blankBlock)
            }
            val ajson = draftRepo.getDraftAttachmentsJson()
            if (ajson.isNotBlank()) {
                val atts = JSONArray(ajson)
                for (i in 0 until atts.length()) {
                    val o = atts.getJSONObject(i)
                    val dataUrl = o.optString("dataUrl")
                    val mime = o.optString("mime")
                    val o2 = JSONObject()
                    o2.put("type", "image")
                    o2.put("src", dataUrl)
                    resultArr.put(o2)
                }
            }
            for (i in 0 until arr.length()) resultArr.put(arr.get(i))
            run {
                val trailingBlank = JSONObject().put("type", "p").put("text", "")
                resultArr.put(trailingBlank)
            }
            val merged = JSONObject().put("format", "blocks_v1").put("blocks", resultArr).toString()
            try {
                val arr2 = resultArr
                var img2=0; var text2=0
                for (i in 0 until arr2.length()) {
                    when(arr2.getJSONObject(i).optString("type")){
                        "image" -> img2++
                        else -> text2++
                    }
                }
                Log.d("SendCurrent", "Merged blocks: total=${arr2.length()} images=${img2} nonImages=${text2}")
            } catch (_: Exception) {}
            merged
        } catch (e: Exception) { blocksJson }

        if (selectedDocId.startsWith("unsynced") || !isOnline) {
            MessageQueueManager.addToQueue(context, selectedDocId, mergedBlocks, true, messageText.take(200))
            val reason = if (!isOnline) "offline - will sync when connected" else "no document URL configured"
            Toast.makeText(context, "Message queued ($reason)", Toast.LENGTH_LONG).show()
            SharedPreferencesHelper.saveRichMessageToHistory(context, html, messageText, emptyList())
            SharedPreferencesHelper.updateMessageHistoryStatus(context, 0, "pending")
            val preview = htmlToPlainSnippetLocal(html, 200).ifBlank { messageText.take(200) }
            SharedPreferencesHelper.saveDocLastMessage(context, selectedDocId, "Queued: $preview")
            refreshDocNotificationIfEnabled(context, selectedDocId)
            onPlainSuccess()
            draftRepo.clearDraft()
            return
        }

        val sessionId = java.util.UUID.randomUUID().toString()
        val dir = context.filesDir
        val jsonFile = java.io.File(dir, "send_$sessionId.json")
        val htmlFile = java.io.File(dir, "send_$sessionId.html")
        jsonFile.writeText(mergedBlocks)
        htmlFile.writeText(html)
        Log.d("SendCurrent", "Session files written: json='${jsonFile.name}', html='${htmlFile.name}'")
        try {
            val attachList = mutableListOf<HistoryAttachment>()
            try {
                val ajson = draftRepo.getDraftAttachmentsJson()
                if (ajson.isNotBlank()) {
                    val atts = JSONArray(ajson)
                    for (i in 0 until atts.length()) {
                        val o = atts.getJSONObject(i)
                        val dataUrl = o.optString("dataUrl")
                        val thumb = try { createPreviewThumbFromDataUrl(dataUrl) } catch (_: Exception) { dataUrl }
                        attachList.add(HistoryAttachment(thumbDataUrl = thumb, dataUrl = null, mime = o.optString("mime")))
                    }
                }
            } catch (_: Exception) {}
            SharedPreferencesHelper.saveSendingRichHistoryItem(context, sessionId, htmlPreview = html, attachments = attachList)
            try {
                val ajson = draftRepo.getDraftAttachmentsJson()
                if (ajson.isNotBlank()) {
                    val atts = JSONArray(ajson)
                    val light = JSONArray()
                    for (i in 0 until atts.length()) {
                        val o = atts.getJSONObject(i)
                        val dataUrl = o.optString("dataUrl")
                        val thumb = try { createPreviewThumbFromDataUrl(dataUrl) } catch (_: Exception) { dataUrl }
                        val lo = JSONObject()
                        lo.put("thumbDataUrl", thumb)
                        lo.put("mime", o.optString("mime"))
                        light.put(lo)
                    }
                    context.getSharedPreferences("send_sessions", Context.MODE_PRIVATE).edit().putString("attachments_$sessionId", light.toString()).apply()
                }
            } catch (_: Exception) {}
            draftRepo.saveDraftAttachmentsJson("[]")
        } catch (_: Exception) {}

        // This path is only reached if we're NOT using hub mode (i.e., for unsynced/offline cases)
        // In the future, could support Apps Script URL for specific use cases
        if (!appsScriptUrl.isNullOrBlank() && selectedDocId.startsWith("unsynced")) {
            // Only use RichSendService for unsynced docs with Apps Script URL
            RichSendService.start(context, sessionId, jsonFile.absolutePath)
            onBackgroundStarted(sessionId)
            Toast.makeText(context, "Sending in background...", Toast.LENGTH_SHORT).show()
        } else {
            // For offline/unsynced without Apps Script, message was already queued above
            Log.d("SendCurrent", "Message queued for later sync")
        }
    } else {
        // Plain text send - always use hub mode
        val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context) ?: "unsynced_1"
        val isOnline = NetworkUtils.isNetworkAvailable(context)

        if (selectedDocId.startsWith("unsynced") || !isOnline) {
            MessageQueueManager.addToQueue(context, selectedDocId, messageText, false, messageText.take(200))
            val reason = if (!isOnline) "offline - will sync when connected" else "no document URL configured"
            Toast.makeText(context, "Message queued ($reason)", Toast.LENGTH_LONG).show()
            SharedPreferencesHelper.saveMessageToHistory(context, messageText)
            SharedPreferencesHelper.updateMessageHistoryStatus(context, 0, "pending")
            SharedPreferencesHelper.saveDocLastMessage(context, selectedDocId, "Queued: ${messageText.take(200)}")
            refreshDocNotificationIfEnabled(context, selectedDocId)
            onPlainSuccess()
            draftRepo.clearDraft()
            return
        } else if (selectedDocId.length > 10 && isOnline) {
            val result = WebAppHubClient.appendPlain(context, selectedDocId, messageText)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    Toast.makeText(context, "Message sent successfully!", Toast.LENGTH_SHORT).show()
                    SharedPreferencesHelper.saveMessageToHistory(context, messageText)
                    SharedPreferencesHelper.saveDocLastMessage(context, selectedDocId, messageText.take(500))
                    refreshDocNotificationIfEnabled(context, selectedDocId)
                    onPlainSuccess()
                    draftRepo.clearDraft()
                }.onFailure { error ->
                    Toast.makeText(context, "Failed to send: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            MessageQueueManager.addToQueue(context, selectedDocId, messageText, false, messageText.take(200))
            Toast.makeText(context, "Message saved locally (will sync when doc URL added)", Toast.LENGTH_LONG).show()
            SharedPreferencesHelper.saveDocLastMessage(context, selectedDocId, "Queued: ${messageText.take(200)}")
            refreshDocNotificationIfEnabled(context, selectedDocId)
            onPlainSuccess()
            draftRepo.clearDraft()
        }
    }
}

// Convert a committed content URI to an attachment data URL, with downscaling rules
private fun uriToAttachment(context: Context, uri: Uri, mime: String?): AttachmentItem {
    val cr = context.contentResolver
    val mt = mime ?: cr.getType(uri) ?: "image/*"
    return try {
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val isGif = mt.equals("image/gif", ignoreCase = true)
        val isSvg = mt.equals("image/svg+xml", ignoreCase = true)
        if (!isGif && !isSvg) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                val maxDim = 3000
                val ratio = minOf(maxDim.toFloat() / bmp.width.toFloat(), maxDim.toFloat() / bmp.height.toFloat(), 1f)
                val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true) else bmp
                val bos = java.io.ByteArrayOutputStream()
                val outMime = if (mt.equals("image/jpeg", true)) "image/jpeg" else "image/png"
                if (outMime == "image/jpeg") scaled.compress(Bitmap.CompressFormat.JPEG, 90, bos) else scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
                val dataUrl = "data:${outMime};base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                AttachmentItem(dataUrl = dataUrl, mime = outMime)
            } else {
                val dataUrl = "data:${mt};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                AttachmentItem(dataUrl = dataUrl, mime = mt)
            }
        } else {
            val dataUrl = "data:${mt};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            AttachmentItem(dataUrl = dataUrl, mime = mt)
        }
    } catch (e: Exception) {
        val dataUrl = "data:${mt};base64,"
        AttachmentItem(dataUrl = dataUrl, mime = mt)
    }
}

fun extractDocIdFromUrl(urlOrId: String): String? {
    val regex = Regex("""/document/d/([a-zA-Z0-9-_]+)""")
    val matchResult = regex.find(urlOrId)
    if (matchResult != null && matchResult.groupValues.size > 1) {
        return matchResult.groupValues[1]
    }
    if (urlOrId.length > 30 && !urlOrId.contains("/") && !urlOrId.contains(" ")) {
        return urlOrId
    }
    return null
}

private fun ensureDocNotification(context: Context, doc: DocEntry) {
    val appContext = context.applicationContext
    SharedPreferencesHelper.saveDocNotificationEnabled(appContext, doc.docId, true)
    if (SharedPreferencesHelper.loadDocLastMessage(appContext, doc.docId).isNullOrBlank()) {
        SharedPreferencesHelper.saveDocLastMessage(appContext, doc.docId, "Ready for new note...")
    }
    if (!doc.docId.startsWith("unsynced")) {
        NotificationUtils.refreshDocumentNotification(appContext, doc.docId)
        val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            ContextCompat.startForegroundService(appContext, Intent(appContext, NotificationService::class.java))
        } else {
            Log.w("MainActivity", "Notification permission missing; service not started for ${doc.docId}")
        }
    }
    appContext.sendBroadcast(Intent(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED))
}

private fun refreshDocNotificationIfEnabled(context: Context, docId: String) {
    if (docId.startsWith("unsynced")) return
    if (SharedPreferencesHelper.loadDocNotificationEnabled(context, docId)) {
        NotificationUtils.refreshDocumentNotification(context, docId)
    }
}

private fun decodeDataUrlToBitmap(dataUrl: String): Bitmap? {
    return try {
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return null
        val base64 = dataUrl.substring(comma + 1)
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

// Create a small preview thumbnail data URL (JPEG) to store in history, avoiding gigantic base64 strings
private fun createPreviewThumbFromDataUrl(dataUrl: String, maxDim: Int = 256): String {
    val bmp = decodeDataUrlToBitmap(dataUrl) ?: return dataUrl
    val ratio = minOf(maxDim.toFloat() / bmp.width.toFloat(), maxDim.toFloat() / bmp.height.toFloat(), 1f)
    val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true) else bmp
    val bos = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
    val base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$base64"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppWithTabs(
    onCheckPermissionAndStartService: () -> Unit,
    context: Context
) {
    // Always use hub mode, ensure at least one doc exists
    var docs by remember {
        val initialDocs = SharedPreferencesHelper.loadDocEntries(context).ifEmpty {
            listOf(DocEntry(docId = "unsynced_1", alias = "Unsynced doc 1"))
        }
        mutableStateOf(initialDocs)
    }
    var notificationsRevision by remember { mutableIntStateOf(0) }
    var selectedDocId by remember { 
        mutableStateOf(SharedPreferencesHelper.loadSelectedDocId(context) ?: docs.firstOrNull()?.docId ?: "unsynced_1") 
    }
    
    // Load saved tab index for current document
    val currentSubTab = selectedDocId?.let {
        SharedPreferencesHelper.loadDocTabState(context, it)
    } ?: 0
    
    // Current selected tab
    var selectedTab by remember { mutableStateOf(currentSubTab) }
    var messageHistory by remember { mutableStateOf(SharedPreferencesHelper.loadMessageHistory(context)) }
    
    // Dialog states
    var showAddDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    
    // Master tab states
    var showMasterTab by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showSuccessDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    DisposableEffect(context) {
        val appContext = context.applicationContext
        val filter = IntentFilter(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                docs = SharedPreferencesHelper.loadDocEntries(appContext)
                val docIds = docs.map { it.docId }
                if (selectedDocId !in docIds) {
                    selectedDocId = docIds.firstOrNull() ?: "unsynced_1"
                    SharedPreferencesHelper.saveSelectedDocId(appContext, selectedDocId)
                }
                notificationsRevision++
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        val onChanged: () -> Unit = {
            notificationsRevision++
        }
        NotificationStateNotifier.register(onChanged)
        onChanged()
        onDispose {
            NotificationStateNotifier.unregister(onChanged)
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
        }
    }
    
    // Fetch config on app startup and periodically
    LaunchedEffect(Unit) {
        scope.launch {
            // Fetch initial config
            WebAppHubClient.fetchConfig(context)
            
            // Set up periodic fetching every 5 minutes
            while (true) {
                delay(5 * 60 * 1000L) // 5 minutes
                Log.d("MainActivity", "Fetching updated config from remote")
                WebAppHubClient.fetchConfig(context)
            }
        }
    }
    
    // Refresh history when tab changes or periodically
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            Log.d("MainActivity", "History tab selected, loading messages...")
            messageHistory = SharedPreferencesHelper.loadMessageHistory(context)
            Log.d("MainActivity", "Loaded ${messageHistory.size} messages from history")
        }
    }
    
    // Auto-refresh history every 2 seconds when on history tab
    LaunchedEffect(selectedTab) {
        while (selectedTab == 1) {
            delay(2000)
            val newHistory = SharedPreferencesHelper.loadMessageHistory(context)
            if (newHistory.size != messageHistory.size) {
                Log.d("MainActivity", "History updated: ${messageHistory.size} -> ${newHistory.size} messages")
                messageHistory = newHistory
            }
        }
    }
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(), // This ensures content is below status bar
        containerColor = MaterialTheme.colorScheme.background, // Use theme background color
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                
                // Document tabs (when in hub mode)
                // Always show document tabs
                if (true) {
                    DocTabsBar(
                        docs = docs,
                        selectedDocId = selectedDocId,
                        onSelectDoc = { docId ->
                            selectedDocId = docId
                            SharedPreferencesHelper.saveSelectedDocId(context, docId)
                            // Restore per-doc sub-tab
                            selectedTab = SharedPreferencesHelper.loadDocTabState(context, docId)
                        },
                        onAddDoc = {
                            showAddDialog = true
                        },
                        onRemoveDoc = { docId ->
                            showRemoveDialog = docId
                        },
                        onRenameDoc = { docId ->
                            showRenameDialog = docId
                        }
                    )
                }
                
                TabRow(
                    selectedTabIndex = if (showMasterTab && selectedTab == 3) 3 else selectedTab.coerceAtMost(2),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { 
                            Log.d("MainActivity", "Setup tab clicked")
                            selectedTab = 0
                            val docId = selectedDocId
                            if (docId != null) {
                                SharedPreferencesHelper.saveDocTabState(context, docId, 0)
                            } else {
                                SharedPreferencesHelper.saveLastTabIndex(context, 0)
                            }
                        },
                        text = { Text("Setup") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { 
                            Log.d("MainActivity", "History tab clicked")
                            selectedTab = 1
                            val docId = selectedDocId
                            if (docId != null) {
                                SharedPreferencesHelper.saveDocTabState(context, docId, 1)
                            } else {
                                SharedPreferencesHelper.saveLastTabIndex(context, 1)
                            }
                            // Reload history when tab is clicked
                            messageHistory = SharedPreferencesHelper.loadMessageHistory(context)
                            Log.d("MainActivity", "Reloaded ${messageHistory.size} messages")
                        },
                        text = { Text("History") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { 
                            Log.d("MainActivity", "Write tab clicked")
                            selectedTab = 2
                            val docId = selectedDocId
                            if (docId != null) {
                                SharedPreferencesHelper.saveDocTabState(context, docId, 2)
                            } else {
                                SharedPreferencesHelper.saveLastTabIndex(context, 2)
                            }
                        },
                        text = { Text("Write") }
                    )
                    
                    // Master tab (only shows when unlocked)
                    if (showMasterTab) {
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { 
                                Log.d("MainActivity", "Master tab clicked")
                                selectedTab = 3
                            },
                            text = { Text("Master") }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier
            .padding(paddingValues)
            .imePadding()
        ) {
            // Tab Content
            when (selectedTab) {
                0 -> SetupScreen(
                    onCheckPermissionAndStartService = onCheckPermissionAndStartService,
                    onShowMasterPassword = { showPasswordDialog = true },
                    docsStateRevision = notificationsRevision,
                    onRequestNotificationsRefresh = { notificationsRevision++ }
                )
                1 -> HistoryScreen(messageHistory = messageHistory)
                2 -> WriteScreen(context = context)
                3 -> if (showMasterTab) {
                    MasterScreen(
                        context = context,
                        onSaveSuccess = { showSuccessDialog = true }
                    )
                }
            }
        }
    }
    
    // Dialogs
    if (showAddDialog) {
        AddDocumentDialog(
            existingDocs = docs,
            onConfirm = { docId, alias ->
                val entry = DocEntry(docId = docId, alias = alias)
                if (SharedPreferencesHelper.addDocEntry(context, entry, 3)) {
                    scope.launch {
                        WebAppHubClient.registerDoc(context, entry.docId,
                            DocConfig(statsBottom = true, timezone = java.util.TimeZone.getDefault().id))
                    }
                    docs = SharedPreferencesHelper.loadDocEntries(context)
                    selectedDocId = docId
                    SharedPreferencesHelper.saveSelectedDocId(context, docId)
                    ensureDocNotification(context, entry)
                    notificationsRevision++
                } else {
                    Toast.makeText(context, "Could not add document (limit reached or duplicate)", Toast.LENGTH_SHORT).show()
                }
                showAddDialog = false
            },
            onDelete = { docId ->
                SharedPreferencesHelper.removeDocEntry(context, docId)
                docs = SharedPreferencesHelper.loadDocEntries(context)
                val appContext = context.applicationContext
                NotificationManagerCompat.from(appContext).cancel(docId.hashCode())
                if (selectedDocId == docId) {
                    selectedDocId = docs.firstOrNull()?.docId ?: "unsynced_1"
                    SharedPreferencesHelper.saveSelectedDocId(context, selectedDocId)
                }
                val anyEnabled = docs.any {
                    !it.docId.startsWith("unsynced") &&
                        SharedPreferencesHelper.loadDocNotificationEnabled(context, it.docId)
                }
                if (!anyEnabled) {
                    appContext.stopService(Intent(appContext, NotificationService::class.java))
                    NotificationManagerCompat.from(appContext).cancel(NotificationUtils.NOTIFICATION_ID)
                }
                appContext.sendBroadcast(Intent(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED))
                notificationsRevision++
            },
            onUpdate = { oldDocId, newDocId ->
                // Update unsynced doc with real Google Doc ID
                val doc = docs.find { it.docId == oldDocId }
                if (doc != null) {
                    SharedPreferencesHelper.removeDocEntry(context, oldDocId)
                    val updatedDoc = doc.copy(docId = newDocId)
                    SharedPreferencesHelper.addDocEntry(context, updatedDoc, 3)
                    docs = SharedPreferencesHelper.loadDocEntries(context)
                    
                    // Update selected doc if it was the one being updated
                    if (selectedDocId == oldDocId) {
                        selectedDocId = newDocId
                        SharedPreferencesHelper.saveSelectedDocId(context, newDocId)
                    }
                    
                    // Sync queued messages for this doc
                    scope.launch {
                        MessageQueueManager.syncAllForDoc(context, newDocId)
                    }
                    ensureDocNotification(context, updatedDoc)
                    notificationsRevision++
                }
            },
            onDismiss = { showAddDialog = false }
        )
    }
    
    showRemoveDialog?.let { docId ->
        val doc = docs.find { it.docId == docId }
        RemoveDocDialog(
            docId = docId,
            docName = doc?.alias?.ifBlank { doc.title } ?: "…${docId.takeLast(6)}",
            onConfirm = {
                scope.launch {
                    // No need to unregister from web app, just remove locally
                }
                SharedPreferencesHelper.removeDocEntry(context, docId)
                docs = SharedPreferencesHelper.loadDocEntries(context)
                val appContext = context.applicationContext
                NotificationManagerCompat.from(appContext).cancel(docId.hashCode())
                if (selectedDocId == docId) {
                    selectedDocId = docs.firstOrNull()?.docId ?: "unsynced_1"
                    SharedPreferencesHelper.saveSelectedDocId(context, selectedDocId)
                }
                val anyEnabled = docs.any {
                    !it.docId.startsWith("unsynced") &&
                        SharedPreferencesHelper.loadDocNotificationEnabled(context, it.docId)
                }
                if (!anyEnabled) {
                    appContext.stopService(Intent(appContext, NotificationService::class.java))
                    NotificationManagerCompat.from(appContext).cancel(NotificationUtils.NOTIFICATION_ID)
                }
                appContext.sendBroadcast(Intent(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED))
                notificationsRevision++
                showRemoveDialog = null
            },
            onDismiss = { showRemoveDialog = null }
        )
    }
    
    showRenameDialog?.let { docId ->
        val doc = docs.find { it.docId == docId }
        RenameDocDialog(
            currentName = doc?.alias ?: "",
            onConfirm = { newName ->
                doc?.let {
                    val updated = it.copy(alias = newName)
                    SharedPreferencesHelper.updateDocEntry(context, updated)
                    docs = SharedPreferencesHelper.loadDocEntries(context)
                    scope.launch {
                        WebAppHubClient.setConfig(context, docId, DocConfig())
                    }
                }
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }
    
    // Password dialog for Master tab access
    if (showPasswordDialog) {
        var checkingPassword by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { 
                showPasswordDialog = false
                passwordInput = ""
            },
            title = { Text("Enter Password") },
            text = {
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !checkingPassword
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        checkingPassword = true
                        scope.launch {
                            // Fetch the current password from remote config
                            val configResult = WebAppHubClient.fetchConfig(context)
                            val masterPassword = if (configResult.isSuccess) {
                                configResult.getOrNull()?.second ?: "sam03"
                            } else {
                                SharedPreferencesHelper.loadMasterPassword(context) ?: "sam03"
                            }
                            
                            if (passwordInput == masterPassword) {
                                showMasterTab = true
                                selectedTab = 3
                                showPasswordDialog = false
                                passwordInput = ""
                                Log.d("MainActivity", "Master tab unlocked")
                            } else {
                                Toast.makeText(context, "Incorrect password", Toast.LENGTH_SHORT).show()
                                passwordInput = ""
                            }
                            checkingPassword = false
                        }
                    },
                    enabled = !checkingPassword
                ) {
                    Text(if (checkingPassword) "Checking..." else "OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showPasswordDialog = false
                        passwordInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Success dialog after saving script URL
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = { Text("Success") },
            text = { Text("All changes have been saved successfully") },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(
    onCheckPermissionAndStartService: () -> Unit,
    onShowMasterPassword: () -> Unit = {},
    docsStateRevision: Int = 0,
    onRequestNotificationsRefresh: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val docs: List<DocEntry> = remember(docsStateRevision) { SharedPreferencesHelper.loadDocEntries(context) }
    val selectedDocId: String? = remember(docsStateRevision) { SharedPreferencesHelper.loadSelectedDocId(context) }
    val selectedDoc = docs.find { it.docId == selectedDocId }
    
    // Reload stats when selected document changes
    var statsTop by remember(selectedDocId) { mutableStateOf(SharedPreferencesHelper.loadStatsTop(context)) }
    var statsBottom by remember(selectedDocId) { mutableStateOf(SharedPreferencesHelper.loadStatsBottom(context)) }
    var statsAnywhere by remember(selectedDocId) { mutableStateOf(SharedPreferencesHelper.loadStatsAnywhere(context)) }
    var timezone by remember { mutableStateOf(SharedPreferencesHelper.loadTimezone(context)) }
    var tzExpanded by remember { mutableStateOf(false) }
    
    // Apply stats when document is selected
    LaunchedEffect(selectedDocId) {
        if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
            val result = WebAppHubClient.setConfig(
                context,
                selectedDocId,
                DocConfig(statsTop = statsTop, statsBottom = statsBottom, statsAnywhere = statsAnywhere, timezone = timezone)
            )
            result.onFailure {
                Log.e("SetupScreen", "Failed to apply initial stats config: ${it.message}")
            }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Document selection info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Selected Document",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (selectedDoc != null && !selectedDoc.docId.startsWith("unsynced")) {
                    Text(
                        selectedDoc.alias.ifEmpty { "Document" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "ID: ${selectedDoc.docId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "No document configured",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Add a document URL in the + tab",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Notification Service button
        Button(
            onClick = onCheckPermissionAndStartService,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Notification Service")
        }
        
        // Document notification checkboxes (only show if documents exist)
        if (docs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Document Notifications",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    docs.forEach { doc ->
                        val isChecked = SharedPreferencesHelper.loadDocNotificationEnabled(context, doc.docId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    SharedPreferencesHelper.saveDocNotificationEnabled(context, doc.docId, checked)
                                    val appContext = context.applicationContext
                                    
                                    // Update notification state
                                    if (checked) {
                                        Log.d("SetupScreen", "Enabling notification for ${doc.alias}")
                                        if (doc.docId.startsWith("unsynced")) {
                                            Toast.makeText(context, "Sync this document before enabling notifications.", Toast.LENGTH_LONG).show()
                                            SharedPreferencesHelper.saveDocNotificationEnabled(context, doc.docId, false)
                                            appContext.sendBroadcast(Intent(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED))
                                            onRequestNotificationsRefresh()
                                        } else {
                                            ensureDocNotification(appContext, doc)
                                            onRequestNotificationsRefresh()
                                        }
                                    } else {
                                        Log.d("SetupScreen", "Disabling notification for ${doc.alias}")
                                        NotificationManagerCompat.from(appContext).cancel(doc.docId.hashCode())
                                        val remainingEnabled = docs.any { other ->
                                            other.docId != doc.docId &&
                                                !other.docId.startsWith("unsynced") &&
                                                SharedPreferencesHelper.loadDocNotificationEnabled(context, other.docId)
                                        }
                                        if (!remainingEnabled) {
                                            appContext.stopService(Intent(appContext, NotificationService::class.java))
                                            NotificationManagerCompat.from(appContext).cancel(NotificationUtils.NOTIFICATION_ID)
                                        } else {
                                            ContextCompat.startForegroundService(appContext, Intent(appContext, NotificationService::class.java))
                                        }
                                        appContext.sendBroadcast(Intent(NotificationReceiver.ACTION_NOTIFICATIONS_UPDATED))
                                        onRequestNotificationsRefresh()
                                    }
                                }
                            )
                            Text(
                                text = doc.alias,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text("STATS", style = MaterialTheme.typography.titleMedium)
    // Tighten spacing under STATS header
    // Checkboxes on one line, plus auto-update controls
    CompositionLocalProvider(
        androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement provides false
    ) {
        Column {
            Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = statsTop,
                    onCheckedChange = { checked ->
                        Log.d("StatsToggle", "=== TOP CHECKBOX CLICKED ===")
                        Log.d("StatsToggle", "New statsTop value: $checked")
                        Log.d("StatsToggle", "Current statsBottom value: $statsBottom")
                        statsTop = checked
                        SharedPreferencesHelper.saveStatsTop(context, checked)
                        scope.launch {
                            val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context)
                            Log.d("StatsToggle", "Selected document ID: $selectedDocId")
                            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
                            Log.d("StatsToggle", "Apps Script URL: $appsScriptUrl")
                            
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                Log.d("StatsToggle", "Calling WebAppHubClient.setConfig for TOP checkbox...")
                                val result = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(statsTop = checked, statsBottom = statsBottom, statsAnywhere = statsAnywhere)
                                )
                                result.onSuccess {
                                    Log.d("StatsToggle", "TOP stats config SUCCESS")
                                    Toast.makeText(context, "Top stats setting applied", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Log.e("StatsToggle", "TOP stats config FAILED: ${it.message}", it)
                                    Toast.makeText(context, "Failed to apply top stats: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Log.w("StatsToggle", "Cannot apply stats - no valid document selected")
                                Toast.makeText(context, "No document selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledUncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Top", style = MaterialTheme.typography.labelSmall)
            }

            // Bottom
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = statsBottom,
                    onCheckedChange = { checked ->
                        Log.d("StatsToggle", "=== BOTTOM CHECKBOX CLICKED ===")
                        Log.d("StatsToggle", "New statsBottom value: $checked")
                        Log.d("StatsToggle", "Current statsTop value: $statsTop")
                        statsBottom = checked
                        SharedPreferencesHelper.saveStatsBottom(context, checked)
                        scope.launch {
                            val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context)
                            Log.d("StatsToggle", "Selected document ID: $selectedDocId")
                            val appsScriptUrl = SharedPreferencesHelper.loadAppsScriptUrl(context)
                            Log.d("StatsToggle", "Apps Script URL: $appsScriptUrl")
                            
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                Log.d("StatsToggle", "Calling WebAppHubClient.setConfig for BOTTOM checkbox...")
                                val result = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(statsTop = statsTop, statsBottom = checked, statsAnywhere = statsAnywhere)
                                )
                                result.onSuccess {
                                    Log.d("StatsToggle", "BOTTOM stats config SUCCESS")
                                    Toast.makeText(context, "Bottom stats setting applied", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Log.e("StatsToggle", "BOTTOM stats config FAILED: ${it.message}", it)
                                    Toast.makeText(context, "Failed to apply bottom stats: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Log.w("StatsToggle", "Cannot apply stats - no valid document selected")
                                Toast.makeText(context, "No document selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledUncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bottom", style = MaterialTheme.typography.labelSmall)
            }

            // Every ⏰⏰
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = statsAnywhere,
                    onCheckedChange = { checked ->
                        Log.d("StatsToggle", "=== ANYWHERE CHECKBOX CLICKED ===")
                        Log.d("StatsToggle", "New statsAnywhere value: $checked")
                        statsAnywhere = checked
                        SharedPreferencesHelper.saveStatsAnywhere(context, checked)
                        scope.launch {
                            val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context)
                            Log.d("StatsToggle", "Selected document ID: $selectedDocId")
                            
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                Log.d("StatsToggle", "Calling WebAppHubClient.setConfig for ANYWHERE checkbox...")
                                val result = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(statsTop = statsTop, statsBottom = statsBottom, statsAnywhere = checked)
                                )
                                result.onSuccess {
                                    Log.d("StatsToggle", "ANYWHERE stats config SUCCESS")
                                    Toast.makeText(context, "Every ⏳ setting applied", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Log.e("StatsToggle", "ANYWHERE stats config FAILED: ${it.message}", it)
                                    Toast.makeText(context, "Failed to apply Every ⏳: ${it.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Log.w("StatsToggle", "Cannot apply stats - no valid document selected")
                                Toast.makeText(context, "No document selected", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledUncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Every ⏳", style = MaterialTheme.typography.labelSmall)
            }

            // Auto-update group (checkbox + label + input)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var autoEnabled by remember { mutableStateOf(SharedPreferencesHelper.loadAutoUpdateEnabled(context)) }
                var autoMinutesText by remember { mutableStateOf(SharedPreferencesHelper.loadAutoUpdateMinutes(context).toString()) }
                val minutesBringIntoView = remember { BringIntoViewRequester() }
                fun normalizeMinutes(n: Int): Int {
                    val allowed = intArrayOf(1, 5, 10, 15, 30)
                    var best = allowed[0]
                    var bestDiff = Int.MAX_VALUE
                    for (v in allowed) {
                        val d = abs(v - n)
                        if (d < bestDiff) { best = v; bestDiff = d }
                    }
                    return best
                }
                Checkbox(
                    checked = autoEnabled,
                    onCheckedChange = { checked ->
                        autoEnabled = checked
                        SharedPreferencesHelper.saveAutoUpdateEnabled(context, checked)
                        var minutes = autoMinutesText.toIntOrNull() ?: 5
                        if (minutes < 1) { minutes = 5; autoMinutesText = minutes.toString() }
                        scope.launch {
                            val selectedDocId = SharedPreferencesHelper.loadSelectedDocId(context)
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                val result = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(
                                        statsTop = statsTop,
                                        statsBottom = statsBottom,
                                        statsAnywhere = statsAnywhere,
                                        timezone = timezone,
                                        autoEnabled = checked,
                                        minutes = minutes
                                    )
                                )
                            result.onSuccess {
                                Toast.makeText(context, if (checked) "Auto-update enabled" else "Auto-update disabled", Toast.LENGTH_SHORT).show()
                                Log.d("SetupStats", "setStatsConfig auto=${checked} minutes=${minutes} ok: ${it}")
                            }.onFailure {
                                Toast.makeText(context, "Failed to update auto-update: ${it.message}", Toast.LENGTH_LONG).show()
                                Log.e("SetupStats", "setStatsConfig auto failed", it)
                            }
                        }
                        }
                    },
                    modifier = Modifier.size(18.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                        disabledUncheckedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Auto-update every", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedTextField(
                    value = autoMinutesText,
                    onValueChange = { v ->
                        val digits = v.filter { it.isDigit() }
                        autoMinutesText = digits
                        SharedPreferencesHelper.saveAutoUpdateMinutes(context, digits.toIntOrNull() ?: 5)
                        if (autoEnabled) {
                                val raw = digits.toIntOrNull() ?: 5
                                val minutes = normalizeMinutes(raw)
                            scope.launch {
                                if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                    val result = WebAppHubClient.setConfig(
                                        context,
                                        selectedDocId,
                                        DocConfig(
                                            statsTop = statsTop,
                                            statsBottom = statsBottom,
                                            timezone = timezone,
                                            autoEnabled = true,
                                            minutes = minutes
                                        )
                                    )
                                result.onSuccess { Log.d("SetupStats", "setStatsConfig minutes=${minutes} ok: ${it}") }
                                    .onFailure { Log.e("SetupStats", "setStatsConfig minutes failed", it) }
                            }
                            }
                        }
                    },
                    modifier = Modifier
                        .width(74.dp)
                        .heightIn(min = 36.dp)
                        .bringIntoViewRequester(minutesBringIntoView)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                scope.launch { minutesBringIntoView.bringIntoView() }
                            }
                        },
                    singleLine = true,
                    enabled = true,
                    textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center),
                    placeholder = { Text("0") },
                    suffix = { Text("min", style = MaterialTheme.typography.labelSmall) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val raw = autoMinutesText.toIntOrNull() ?: 5
                        val minutes = normalizeMinutes(raw)
                        // Update field text to the normalized value
                        autoMinutesText = minutes.toString()
                        SharedPreferencesHelper.saveAutoUpdateMinutes(context, minutes)
                        scope.launch {
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                val result = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(
                                        statsTop = statsTop,
                                        statsBottom = statsBottom,
                                        statsAnywhere = statsAnywhere,
                                        timezone = timezone,
                                        autoEnabled = autoEnabled,
                                        minutes = minutes
                                    )
                                )
                                result.onSuccess { Log.d("SetupStats", "setStatsConfig onDone minutes=${minutes} enabled=${autoEnabled} ok: ${it}") }
                                    .onFailure { Log.e("SetupStats", "setStatsConfig onDone failed", it) }
                            }
                        }
                        focusManager.clearFocus()
                    })
                )
            }
        }
        
        // Timezone selector
        Text("Timezone", style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(expanded = tzExpanded, onExpandedChange = { tzExpanded = it }) {
            val tzOptions = remember { java.util.TimeZone.getAvailableIDs().toList().sorted() }
            OutlinedTextField(
                value = timezone,
                onValueChange = {},
                readOnly = true,
                label = { Text("Select Timezone") },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tzExpanded) }
            )
            ExposedDropdownMenu(expanded = tzExpanded, onDismissRequest = { tzExpanded = false }) {
                tzOptions.forEach { tz ->
                    DropdownMenuItem(text = { Text(tz) }, onClick = {
                        timezone = tz
                        SharedPreferencesHelper.saveTimezone(context, tz)
                        // Send to server without redeploy
                        scope.launch {
                            if (selectedDocId != null && !selectedDocId.startsWith("unsynced")) {
                                val res = WebAppHubClient.setConfig(
                                    context,
                                    selectedDocId,
                                    DocConfig(
                                        statsTop = statsTop,
                                        statsBottom = statsBottom,
                                        timezone = tz
                                    )
                                )
                                res.onSuccess { Log.d("SetupStats", "setStatsConfig timezone=$tz ok: ${it}") }
                                    .onFailure { Log.e("SetupStats", "setStatsConfig timezone failed", it) }
                            }
                        }
                        tzExpanded = false
                    })
                }
            }
        }
        
        // Hidden image button for Master tab access
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
            var isHolding by remember { mutableStateOf(false) }
            var holdJob by remember { mutableStateOf<Job?>(null) }
            
            Image(
                painter = painterResource(id = R.drawable.app_icon_bottom),
                contentDescription = null, // Hidden button, no description needed
                modifier = Modifier
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            isHolding = true
                            
                            // Use the scope from SetupScreen to launch coroutine
                            holdJob = scope.launch {
                                delay(3000) // 3 seconds
                                if (isHolding) {
                                    // Vibrate to indicate success
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                                    } else {
                                        @Suppress("DEPRECATION")
                                        vibrator.vibrate(100)
                                    }
                                    onShowMasterPassword()
                                }
                            }
                            
                            // Wait for release or cancellation
                            waitForUpOrCancellation()
                            isHolding = false
                            holdJob?.cancel()
                            holdJob = null
                        }
                    }
            )
        }
        }
    }
    }
    // Removed Apply/Install buttons; all actions are automatic via checkboxes and fields above
}

@Composable
fun MasterScreen(context: Context, onSaveSuccess: () -> Unit) {
    var hubUrl by remember { mutableStateOf("") }
    var configUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var networkError by remember { mutableStateOf(false) }
    var hubUrlError by remember { mutableStateOf(false) }
    var configUrlError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Fetch config on load
    LaunchedEffect(Unit) {
        scope.launch {
            val result = WebAppHubClient.fetchConfig(context)
            if (result.isSuccess) {
                val (hub, _, config) = result.getOrNull() ?: Triple("", "", "")
                hubUrl = hub
                configUrl = config
                networkError = false
            } else {
                // Use local fallback
                hubUrl = SharedPreferencesHelper.loadHubUrl(context) ?: ""
                configUrl = SharedPreferencesHelper.loadConfigUrl(context) ?: WebAppHubClient.CONFIG_URL
                networkError = true
            }
            isLoading = false
        }
    }
    
    // Validate URL format
    fun isValidScriptUrl(url: String): Boolean {
        return url.isBlank() || 
               (url.startsWith("https://script.google.com/") && 
                url.contains("/exec"))
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Master Configuration",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Config Server URL Field
        Text(
            "Config Server URL",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        OutlinedTextField(
            value = configUrl,
            onValueChange = { 
                configUrl = it
                configUrlError = !isValidScriptUrl(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = when {
                        networkError -> Color(0xFFFFA500)
                        configUrlError -> Color(0xFFFFA500)
                        else -> Color(0xFF98FB98)
                    },
                    shape = RoundedCornerShape(8.dp)
                ),
            placeholder = { Text("Config script URL (controls all devices)") },
            singleLine = false,
            isError = configUrlError,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = when {
                    networkError || configUrlError -> Color(0xFFFFA500)
                    else -> Color(0xFF98FB98)
                },
                unfocusedBorderColor = when {
                    networkError || configUrlError -> Color(0xFFFFA500)
                    else -> Color(0xFF98FB98)
                },
                errorBorderColor = Color(0xFFFFA500),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            supportingText = {
                when {
                    configUrlError -> Text(
                        "URL must start with https://script.google.com/ and contain /exec",
                        color = Color(0xFFFFA500)
                    )
                }
            },
            enabled = !isLoading
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Hub Script URL Field
        Text(
            "Hub Script URL",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        OutlinedTextField(
            value = hubUrl,
            onValueChange = { 
                hubUrl = it
                hubUrlError = !isValidScriptUrl(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = when {
                        networkError -> Color(0xFFFFA500)
                        hubUrlError -> Color(0xFFFFA500)
                        else -> Color(0xFF98FB98)
                    },
                    shape = RoundedCornerShape(8.dp)
                ),
            placeholder = { Text("Hub script URL (handles document operations)") },
            singleLine = false,
            isError = hubUrlError,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = when {
                    networkError || hubUrlError -> Color(0xFFFFA500)
                    else -> Color(0xFF98FB98)
                },
                unfocusedBorderColor = when {
                    networkError || hubUrlError -> Color(0xFFFFA500)
                    else -> Color(0xFF98FB98)
                },
                errorBorderColor = Color(0xFFFFA500),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            supportingText = {
                when {
                    networkError -> Text(
                        "Network error - using cached configuration",
                        color = Color(0xFFFFA500)
                    )
                    hubUrlError -> Text(
                        "URL must start with https://script.google.com/ and contain /exec",
                        color = Color(0xFFFFA500)
                    )
                }
            },
            enabled = !isLoading
        )
        
        Text(
            "Config Server URL: Controls which hub all devices use (change this to redirect all users)\n" +
            "Hub Script URL: The actual script that handles document operations",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        
        Button(
            onClick = {
                if (!hubUrlError && !configUrlError) {
                    scope.launch {
                        networkError = false
                        
                        // First, save the config URL locally
                        SharedPreferencesHelper.saveConfigUrl(context, configUrl)
                        
                        // Get current password
                        val configResult = WebAppHubClient.fetchConfig(context)
                        val currentPassword = configResult.getOrNull()?.second ?: "sam03"
                        
                        // Update current config to point to new config (if changed)
                        if (configUrl != (configResult.getOrNull()?.third ?: WebAppHubClient.CONFIG_URL)) {
                            // Update old config to redirect to new config
                            val updateRedirect = WebAppHubClient.updateConfig(
                                context, 
                                nextConfigUrl = configUrl
                            )
                            if (updateRedirect.isFailure) {
                                Log.e("MasterScreen", "Failed to update redirect: ${updateRedirect.exceptionOrNull()}")
                            }
                        }
                        
                        // Update the hub URL at the current config
                        val result = WebAppHubClient.updateConfig(context, hubUrl = hubUrl)
                        if (result.isSuccess) {
                            Log.d("MasterScreen", "Saved configuration successfully")
                            onSaveSuccess()
                            networkError = false
                        } else {
                            Toast.makeText(context, "Failed to update config: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            networkError = true
                        }
                    }
                } else {
                    Toast.makeText(context, "Please fix URL errors before saving", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (hubUrlError || configUrlError) Color.Gray else Color(0xFF98FB98),
                contentColor = Color.Black
            ),
            enabled = !hubUrlError && !configUrlError && !isLoading,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "Save",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun HistoryScreen(messageHistory: List<MessageHistoryItem>) {
    Log.d("HistoryScreen", "Rendering history with ${messageHistory.size} messages")
    
    if (messageHistory.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No messages sent yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Send a message through the notification to see it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messageHistory) { item ->
                Log.d("HistoryScreen", "Rendering message: ${item.text.take(50)}...")
                MessageCard(item)
            }
        }
    }
}

@Composable
fun MessageCard(item: MessageHistoryItem) {
    var showDialog by remember { mutableStateOf(false) }
    
    // Determine sync status icon and color
    val (statusIcon, statusColor) = when (item.status) {
        "sent" -> "✓" to Color(0xFF4CAF50) // Green checkmark
        "sending" -> "⏳" to Color(0xFFFFA500) // Orange hourglass
        "failed" -> "❌" to Color(0xFFF44336) // Red X
        "pending" -> "⏰" to Color(0xFF9E9E9E) // Gray clock
        else -> "" to Color.Transparent
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .border(
                width = 1.dp,
                color = Color(0xFF98FB98), // Pale green
                shape = RoundedCornerShape(8.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.5f) // Black with 50% transparency
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Show sync status with icon
            if (statusIcon.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = statusIcon,
                        color = statusColor,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (item.status) {
                            "sent" -> "Synced"
                            "sending" -> "Syncing..."
                            "failed" -> "Sync failed"
                            "pending" -> "Pending sync"
                            else -> ""
                        },
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (item.attachments.isNotEmpty()) {
                // Thumbnails row (do not block text preview below)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.attachments.take(4).forEach { att ->
                        var thumbDialog by remember { mutableStateOf(false) }
                        val bmp = remember(att.thumbDataUrl) { decodeDataUrlToBitmap(att.thumbDataUrl) }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .border(1.dp, Color(0xFF98FB98), RoundedCornerShape(6.dp))
                                .clickable { thumbDialog = true }
                        ) {
                            if (bmp != null) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = "attachment", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                            }
                        }
                        if (thumbDialog) {
                            androidx.compose.ui.window.Dialog(onDismissRequest = { thumbDialog = false }) {
                                Box(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.9f)).padding(16.dp)) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                            TextButton(onClick = { thumbDialog = false }, modifier = Modifier.navigationBarsPadding()) { Text("Back") }
                                        }
                                        val isGif = (att.mime ?: "").contains("gif", ignoreCase = true)
                                        if (isGif) {
                                            AndroidView(factory = { ctx ->
                                                android.webkit.WebView(ctx).apply {
                                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                                    val src = att.dataUrl ?: att.thumbDataUrl
                                                    loadDataWithBaseURL(null, "<html><body style='margin:0;background:transparent'><img src='$src' style='width:100%;height:auto;'/></body></html>", "text/html", "utf-8", null)
                                                }
                                            }, update = { wv ->
                                                val src = att.dataUrl ?: att.thumbDataUrl
                                                wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                                wv.loadDataWithBaseURL(null, "<html><body style='margin:0;background:transparent'><img src='$src' style='width:100%;height:auto;'/></body></html>", "text/html", "utf-8", null)
                                            }, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            val fullBmp = remember(att.dataUrl ?: att.thumbDataUrl) { decodeDataUrlToBitmap(att.dataUrl ?: att.thumbDataUrl) }
                                            if (fullBmp != null) {
                                                Image(bitmap = fullBmp.asImageBitmap(), contentDescription = "image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Styled preview for HTML content (Option A): only when there are no attachments
            if (item.attachments.isEmpty() && item.format == "html" && !item.html.isNullOrBlank()) {
                val context = LocalContext.current
                AndroidView(
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = false
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                            loadDataWithBaseURL(null, buildPreviewHtml(item.html!!), "text/html", "utf-8", null)
                        }
                    },
                    update = { wv ->
                        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        wv.loadDataWithBaseURL(null, buildPreviewHtml(item.html!!), "text/html", "utf-8", null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Text snippet preview (Option A):
            // - If attachments are present, show snippet under thumbnails (no HTML WebView).
            // - If no attachments and HTML exists, we already show HTML preview above (no snippet).
            // - For plain items, show the snippet.
            val showHtmlPreview = item.attachments.isEmpty() && item.format == "html" && !item.html.isNullOrBlank()
            val snippet = remember(item.text, item.html) {
                if (!item.text.isNullOrBlank()) {
                    val paras = item.text.split("\n\n", "\n").filter { it.isNotBlank() }
                    paras.take(3).joinToString("\n\n").let { if (it.length > 1000) it.take(1000) + "…" else it }
                } else if (!item.html.isNullOrBlank()) {
                    htmlToPlainSnippetLocal(item.html!!, 200)
                } else ""
            }
            if (!showHtmlPreview && snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = snippet,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = item.dateTimeString,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Close button aligned top end, above nav bar via padding in parent container
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDialog = false }, modifier = Modifier.navigationBarsPadding()) {
                            Text("Close")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (item.format == "html" && !item.html.isNullOrBlank()) {
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    settings.javaScriptEnabled = false
                                    loadDataWithBaseURL(null, wrapHtml(item.html!!), "text/html", "utf-8", null)
                                }
                            },
                            update = { wv ->
                                wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                wv.loadDataWithBaseURL(null, wrapHtml(item.html!!), "text/html", "utf-8", null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = item.text,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun buildPreviewHtml(html: String): String {
    // Very simple extraction of first 3 blocks by common tags
    val pattern = Regex("<(p|div|h1|h2|h3|h4|h5|h6|li)([\\s>][\\s\\S]*?)</(p|div|h1|h2|h3|h4|h5|h6|li)>", RegexOption.IGNORE_CASE)
    val matches = pattern.findAll(html).take(3).toList()
    val content = if (matches.isNotEmpty()) matches.joinToString("\n") { it.value } else html.take(1000)
    return wrapHtml(content)
}

private fun wrapHtml(body: String): String = """
<!DOCTYPE html><html><head>
<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />
<style>body{color:#fff;background:transparent;font-family:sans-serif;} a{color:#64B5F6;} img{max-width:100%;height:auto;}</style>
</head><body>$body</body></html>
"""

private fun htmlToPlainSnippetLocal(html: String, maxLen: Int = 200): String {
    var s = html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("</(p|div|h[1-6]|li|br|tr)>", RegexOption.IGNORE_CASE), "\n")
    s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    s = s.replace(Regex("<[^>]+>"), "")
    s = s.replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    s = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    return if (s.length > maxLen) s.take(maxLen) + "…" else s
}


data class AttachmentItem(val dataUrl: String, val mime: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WriteScreen(context: Context) {
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val appContext = LocalContext.current.applicationContext
    val draftRepo = remember { com.samc.replynoteapp.data.DraftRepository(
        com.samc.replynoteapp.data.AppDatabase.getInstance(appContext).draftDao()
    ) }
    
    // Detect keyboard visibility using ime insets
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardHeight = imeInsets.getBottom(density)
    val configuration = LocalConfiguration.current
    val maxInputHeight = maxOf(220.dp, configuration.screenHeightDp.dp * 0.4f)
    // When IME is visible, shrink max height so the inline slider sits closer to the input
    val adjustedMaxInputHeight = if (keyboardHeight > 0) maxOf(220.dp, configuration.screenHeightDp.dp * 0.28f) else maxInputHeight
    
    // State variables
    var messageText by remember { mutableStateOf("") }
    var selectedFont by remember { mutableStateOf(SharedPreferencesHelper.loadSelectedFont(context)) }
    var fontSize by remember { mutableStateOf(SharedPreferencesHelper.loadFontSize(context).toFloat()) }
    var isSending by remember { mutableStateOf(false) }
    var fontDropdownExpanded by remember { mutableStateOf(false) }
    var richHtml by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf(listOf<AttachmentItem>()) }
    var richWebView: android.webkit.WebView? by remember { mutableStateOf(null) }

    // Debug: log IME visibility and selected font changes
    LaunchedEffect(keyboardHeight, selectedFont) {
        Log.d(
            "WriteScreen",
            "keyboardHeight=${keyboardHeight}; selectedFont=${selectedFont}; inlineSlider=${keyboardHeight > 0}; adjustedMaxInputHeight=${adjustedMaxInputHeight}; sendReserve=${if (keyboardHeight > 0) 80 else 0}dp"
        )
    }

    // In-app sending dialog state
    var showSendDialog by remember { mutableStateOf(false) }
    var sendDialogPhase by remember { mutableStateOf("preparing") } // preparing | uploading | error | done
    var sendDialogSessionId by remember { mutableStateOf<String?>(null) }
    var sendDialogCurrent by remember { mutableStateOf(0) }
    var sendDialogTotal by remember { mutableStateOf(0) }
    var sendDialogError by remember { mutableStateOf<String?>(null) }
    
    // Font families
    val interFont = FontFamily(Font(R.font.inter_variable))
    val modakFont = FontFamily(Font(R.font.modak))
    val currentFontFamily = if (selectedFont == "Modak") modakFont else interFont
    
    // Slider mapping: 0..35 -> 2sp..38sp (min smaller per request)
    val minSp = 2f
    val maxSp = 38f
    val sliderMax = 35f
    val calculatedFontSize = (minSp + (fontSize * (maxSp - minSp) / sliderMax)).sp
    
    // One-time migration from SharedPreferences draft to Room, then load Room draft
    LaunchedEffect(Unit) {
        val format = draftRepo.getDraftFormat()
        if (format == "html") {
            richHtml = draftRepo.getDraftHtml()
            // load attachments
            try {
                val ajson = draftRepo.getDraftAttachmentsJson()
                if (ajson.isNotBlank()) {
                    val arr = JSONArray(ajson)
                    val list = mutableListOf<AttachmentItem>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(AttachmentItem(o.optString("dataUrl"), o.optString("mime")))
                    }
                    attachments = list
                }
            } catch (_: Exception) {}
        } else {
            // migrate if Room is empty but SharedPreferences has a draft
            val roomDraft = draftRepo.getDraftText()
            if (roomDraft.isBlank()) {
                val prefsDraft = SharedPreferencesHelper.loadDraftText(context)
                if (prefsDraft.isNotBlank()) {
                    draftRepo.saveDraft(prefsDraft)
                    SharedPreferencesHelper.clearDraftText(context)
                }
            }
            // Load final value
            messageText = draftRepo.getDraftText()
        }
    }

    // Debounced draft saving
    var saveJob by remember { mutableStateOf<Job?>(null) }

    // Ensure rich editor reflects current slider size initially and on changes (device-only)
    LaunchedEffect(selectedFont, richWebView) {
        if (selectedFont == "Original Format" && richWebView != null) {
            val spVal = minSp + (fontSize * (maxSp - minSp) / sliderMax)
            val px = with(density) { spVal.sp.toPx() }
            val fs = px.toInt().coerceAtLeast(2)
            val lh = (fs * 1.4f).toInt()
            try {
                val js = "(function(){var e=document.getElementById('editor'); if(e){e.style.fontSize='${fs}px'; e.style.lineHeight='${lh}px';}})();"
                richWebView?.evaluateJavascript(js, null)
                Log.d("WriteScreen", "Initial CSS apply (Original): slider=${fontSize} sp=${spVal} fsPx=${fs} lh=${lh}")
            } catch (_: Exception) {}
        }
    }
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        val sendReserve = if (keyboardHeight > 0) 80.dp else 0.dp
        val baseColumnModifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(bottom = sendReserve)
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
        val columnModifier = if (selectedFont == "Original Format") baseColumnModifier else baseColumnModifier.verticalScroll(rememberScrollState())

        Column(
            modifier = columnModifier,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Font Selector Dropdown
        ExposedDropdownMenuBox(
            expanded = fontDropdownExpanded,
            onExpandedChange = { fontDropdownExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedFont,
                onValueChange = {},
                readOnly = true,
                label = { Text("Font") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fontDropdownExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = fontDropdownExpanded,
                onDismissRequest = { fontDropdownExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Inter", fontFamily = interFont) },
                    onClick = {
                        selectedFont = "Inter"
                        SharedPreferencesHelper.saveSelectedFont(context, "Inter")
                        fontDropdownExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Modak", fontFamily = modakFont) },
                    onClick = {
                        selectedFont = "Modak"
                        SharedPreferencesHelper.saveSelectedFont(context, "Modak")
                        fontDropdownExpanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Original Format") },
                    onClick = {
                        selectedFont = "Original Format"
                        SharedPreferencesHelper.saveSelectedFont(context, "Original Format")
                        fontDropdownExpanded = false
                    }
                )
            }
        }
        
        if (selectedFont == "Original Format") {
            // Attachments bar
            if (attachments.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachments.forEachIndexed { idx, att ->
                        val bmp = remember(att.dataUrl) { decodeDataUrlToBitmap(att.dataUrl) }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .border(1.dp, Color(0xFF98FB98), RoundedCornerShape(6.dp))
                        ) {
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "attachment",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                            IconButton(
                                onClick = {
                                    attachments = attachments.toMutableList().also { it.removeAt(idx) }
                                    scope.launch {
                                        val arr = JSONArray()
                                        attachments.forEach { a -> arr.put(JSONObject().put("dataUrl", a.dataUrl).put("mime", a.mime)) }
                                        draftRepo.saveDraftAttachmentsJson(arr.toString())
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .background(Color(0xFF98FB98), RoundedCornerShape(12.dp))
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Black)
                            }
                        }
                    }
                }
            }

            // Optional: Attach button as fallback when IME paste not available
            val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                scope.launch {
                    uris?.forEach { uri ->
                        if (attachments.size < 10) {
                            val item = uriToAttachment(context, uri, null)
                            attachments = attachments + item
                        }
                    }
                    val arr = JSONArray()
                    attachments.forEach { a -> arr.put(JSONObject().put("dataUrl", a.dataUrl).put("mime", a.mime)) }
                    draftRepo.saveDraftAttachmentsJson(arr.toString())
                }
            }
            com.samc.replynoteapp.ui.RichEditor(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = adjustedMaxInputHeight),
                initialHtml = richHtml,
                onHtmlChanged = { html ->
                    richHtml = html
                    saveJob?.cancel()
                    saveJob = scope.launch {
                        delay(750)
                        draftRepo.saveDraftHtml(richHtml)
                    }
                },
                onImeImage = { uri, mime ->
                    scope.launch {
                        val item = uriToAttachment(context, uri, mime)
                        if (attachments.size < 10) {
                            attachments = attachments + item
                            val arr = JSONArray()
                            attachments.forEach { a -> arr.put(JSONObject().put("dataUrl", a.dataUrl).put("mime", a.mime)) }
                            draftRepo.saveDraftAttachmentsJson(arr.toString())
                        } else {
                            Toast.makeText(context, "Max 10 images", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                provideWebView = { wv ->
                    richWebView = wv
                }
            )

            // When the keyboard is visible, pin the Font Size slider directly under the editor
            if (keyboardHeight > 0) {
                Log.d("WriteScreen", "Render inline slider (Original Format)")
                Column {
                    Text(
                        "Font Size",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = fontSize,
                        onValueChange = { 
                            fontSize = it
                            SharedPreferencesHelper.saveFontSize(context, it.toInt())
                            // Apply device-only font sizing to the rich editor when in Original Format
                            val spVal = minSp + (it * (maxSp - minSp) / sliderMax)
                            val px = with(density) { spVal.sp.toPx() }
                            val fs = px.toInt().coerceAtLeast(2)
                            val lh = (fs * 1.4f).toInt()
                            try {
                                val js = "(function(){var e=document.getElementById('editor'); if(e){e.style.fontSize='${fs}px'; e.style.lineHeight='${lh}px';}})();"
                                richWebView?.evaluateJavascript(js, null)
                                Log.d("WriteScreen", "Slider change (Original inline): value=${it} sp=${spVal} fsPx=${fs} lh=${lh}")
                            } catch (_: Exception) {}
                        },
                        valueRange = 0f..35f,
                        steps = 34,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Size: ${fontSize.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            // Attach Image button should NOT appear under the text input when keyboard is up
            // Temporarily disabled image attachment feature
            // if (keyboardHeight <= 0) {
            //     Log.d("WriteScreen", "Show Attach Image button (keyboard hidden)")
            //     TextButton(onClick = { pickImages.launch(arrayOf("image/*")) }) { Text("Attach Image") }
            // } else {
            //     Log.d("WriteScreen", "Hide Attach Image button (keyboard visible)")
            // }
        } else {
            // Large Text Input Field
            OutlinedTextField(
                value = messageText,
                onValueChange = { 
                    if (it.length <= 200000) {
                        messageText = it
                        // debounce save
                        saveJob?.cancel()
                        saveJob = scope.launch {
                            delay(750)
                            draftRepo.saveDraft(messageText)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = adjustedMaxInputHeight)
                    .border(
                        width = 1.dp,
                        color = Color(0xFF98FB98), // Pale green
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    fontFamily = currentFontFamily,
                    fontSize = calculatedFontSize,
                    lineHeight = (calculatedFontSize.value * 1.4f).sp
                ),
                placeholder = { 
                    Text(
                        "Type your message here...",
                        fontFamily = currentFontFamily,
                        fontSize = calculatedFontSize
                    )
                },
                supportingText = {
                    Text(
                        "${messageText.length} / 200000",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF98FB98),
                    unfocusedBorderColor = Color(0xFF98FB98)
                ),
                shape = RoundedCornerShape(8.dp),
                maxLines = Int.MAX_VALUE
            )

            // When the keyboard is visible, pin the Font Size slider directly under the text field
            if (keyboardHeight > 0) {
                Log.d("WriteScreen", "Render inline slider (Plain Text)")
                Column {
                    Text(
                        "Font Size",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = fontSize,
                        onValueChange = { 
                            fontSize = it
                            SharedPreferencesHelper.saveFontSize(context, it.toInt())
                        },
                        valueRange = 0f..35f,
                        steps = 34, // 35 increments (0-35)
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Size: ${fontSize.toInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
        
        // Default Font Size Slider placement when keyboard is not visible
        if (keyboardHeight <= 0) {
            Log.d("WriteScreen", "Render default slider placement")
            Column {
                Text(
                    "Font Size",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Slider(
                    value = fontSize,
                    onValueChange = { 
                        fontSize = it
                        SharedPreferencesHelper.saveFontSize(context, it.toInt())
                        // Apply device-only font sizing to the rich editor when in Original Format
                        if (selectedFont == "Original Format") {
                            val spVal = minSp + (it * (maxSp - minSp) / sliderMax)
                            val px = with(density) { spVal.sp.toPx() }
                            val fs = px.toInt().coerceAtLeast(2)
                            val lh = (fs * 1.4f).toInt()
                            try {
                                val js = "(function(){var e=document.getElementById('editor'); if(e){e.style.fontSize='${fs}px'; e.style.lineHeight='${lh}px';}})();"
                                richWebView?.evaluateJavascript(js, null)
                                Log.d("WriteScreen", "Slider change (Original default): value=${it} sp=${spVal} fsPx=${fs} lh=${lh}")
                            } catch (_: Exception) {}
                        }
                    },
                    valueRange = 0f..35f,
                    steps = 34, // 35 increments (0-35)
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Size: ${fontSize.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Send Button
        Button(
            onClick = {
                if (!isSending) {
                    Log.d("SendButton", "Send clicked - setting isSending=true immediately")
                    isSending = true
                    focusManager.clearFocus()
                    if (selectedFont == "Original Format") {
                        showSendDialog = true
                        sendDialogPhase = "preparing"
                        sendDialogSessionId = null
                        sendDialogCurrent = 0
                        sendDialogTotal = 0
                        sendDialogError = null
                    }
                    scope.launch {
                        Log.d("SendButton", "Coroutine started - calling sendCurrent")
                        sendCurrent(
                            context = context,
                            selectedFont = selectedFont,
                            messageText = messageText,
                            richWebView = richWebView,
                            draftRepo = draftRepo,
                            onPlainSuccess = {
                                // Clear UI state on plain success
                                messageText = ""
                                // For Original Format, also clear rich content and attachments
                                if (selectedFont == "Original Format") {
                                    richHtml = ""
                                    attachments = emptyList()
                                }
                            },
                            onBackgroundStarted = { sid ->
                                // Clear editor + draft and switch to uploading phase
                                richHtml = ""
                                try { richWebView?.evaluateJavascript("window.__setContent(\"\")", null) } catch (_: Exception) {}
                                scope.launch { draftRepo.clearDraft() }
                                isSending = false
                                sendDialogSessionId = sid
                                sendDialogPhase = "uploading"
                            }
                        )
                        // Always set isSending=false after sendCurrent completes
                        // Hub mode is now used for Original Format, so we always reset here
                        Log.d("SendButton", "Send complete - setting isSending=false")
                        isSending = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .navigationBarsPadding(),
            enabled = ((selectedFont == "Original Format" && (richHtml.isNotBlank() || attachments.isNotEmpty())) || (selectedFont != "Original Format" && messageText.isNotBlank())) && !isSending,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isSending) Color(0xFF98FB98) else if (messageText.isNotBlank() || (selectedFont == "Original Format" && (richHtml.isNotBlank() || attachments.isNotEmpty()))) MaterialTheme.colorScheme.primary else Color.Gray,
                contentColor = if (isSending) Color.Black else MaterialTheme.colorScheme.onPrimary
            )
        ) {
            if (isSending) {
                Text("Sending")
            } else {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Send Message", fontSize = 16.sp)
            }
        }
        
    }

        // Floating send button when keyboard is visible (overlays content)
        if (keyboardHeight > 0) {
            Button(
                onClick = {
                    if (!isSending) {
                        Log.d("SendButton", "Floating send clicked - setting isSending=true immediately")
                        isSending = true
                        // Keep focus so keyboard stays open for continued typing
                        if (selectedFont == "Original Format") {
                            showSendDialog = true
                            sendDialogPhase = "preparing"
                            sendDialogSessionId = null
                            sendDialogCurrent = 0
                            sendDialogTotal = 0
                            sendDialogError = null
                        }
                        scope.launch {
                            Log.d("SendButton", "Floating coroutine started - calling sendCurrent")
                            sendCurrent(
                                context = context,
                                selectedFont = selectedFont,
                                messageText = messageText,
                                richWebView = richWebView,
                                draftRepo = draftRepo,
                                onPlainSuccess = {
                                    messageText = ""
                                    // For Original Format, also clear rich content and attachments
                                    if (selectedFont == "Original Format") {
                                        richHtml = ""
                                        attachments = emptyList()
                                    }
                                },
                                onBackgroundStarted = { sid ->
                                    richHtml = ""
                                    try { richWebView?.evaluateJavascript("window.__setContent(\"\")", null) } catch (_: Exception) {}
                                    scope.launch { draftRepo.clearDraft() }
                                    isSending = false
                                    sendDialogSessionId = sid
                                    sendDialogPhase = "uploading"
                                }
                            )
                            // Always set isSending=false after sendCurrent completes
                            // Hub mode is now used for Original Format, so we always reset here
                            Log.d("SendButton", "Floating send complete - setting isSending=false")
                            isSending = false
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = !isSending && (
                    (selectedFont == "Original Format" && (richHtml.isNotBlank() || attachments.isNotEmpty())) ||
                    (selectedFont != "Original Format" && messageText.isNotBlank())
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSending) Color(0xFF98FB98) else MaterialTheme.colorScheme.primary,
                    contentColor = if (isSending) Color.Black else MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (isSending) {
                    Text("Sending")
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Send")
                }
            }
        }
    }
}
