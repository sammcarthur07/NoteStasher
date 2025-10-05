package com.vibecode.notestasher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onDocumentAdded: (docId: String, alias: String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var docUrlInput by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    Dialog(
        onDismissRequest = { /* Can't dismiss welcome screen */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .imePadding() // Ensures content is above keyboard
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(0.2f))
                
                // App Title
                Text(
                    text = "Note Stasher",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Subtitle
                Text(
                    text = "Your thoughts, directly to Google Docs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                // Instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "How it works:",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("1. Enter your Google Doc URL below", style = MaterialTheme.typography.bodyMedium)
                        Text("2. Write your thoughts in the app", style = MaterialTheme.typography.bodyMedium)
                        Text("3. They automatically sync to your doc", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                // URL Input
                OutlinedTextField(
                    value = docUrlInput,
                    onValueChange = { 
                        docUrlInput = it
                        errorMessage = null
                    },
                    label = { Text("Google Doc URL") },
                    placeholder = { Text("https://docs.google.com/document/d/...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    isError = errorMessage != null,
                    supportingText = {
                        errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Skip button
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                        enabled = !isValidating
                    ) {
                        Text("Skip for now")
                    }
                    
                    // Continue button
                    Button(
                        onClick = {
                            val docId = extractDocId(docUrlInput)
                            if (docId != null) {
                                isValidating = true
                                // Try to fetch document title
                                scope.launch {
                                    val title = WebAppHubClient.fetchDocumentTitle(docId) ?: "My Document"
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        onDocumentAdded(docId, title)
                                        isValidating = false
                                    }
                                }
                            } else {
                                errorMessage = "Invalid Google Doc URL"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = docUrlInput.isNotEmpty() && !isValidating
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Continue")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.weight(0.3f))
                
                // Footer note
                Text(
                    text = "You can add more documents later from the app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

fun extractDocId(url: String): String? {
    // Extract document ID from various Google Docs URL formats
    val patterns = listOf(
        Regex("docs\\.google\\.com/document/d/([a-zA-Z0-9-_]+)"),
        Regex("^([a-zA-Z0-9-_]+)$") // Just the ID
    )
    
    for (pattern in patterns) {
        val match = pattern.find(url.trim())
        if (match != null) {
            return match.groupValues[1]
        }
    }
    
    return null
}
