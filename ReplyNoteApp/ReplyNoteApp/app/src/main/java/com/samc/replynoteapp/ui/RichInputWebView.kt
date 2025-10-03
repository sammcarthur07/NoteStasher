package com.samc.replynoteapp.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.ByteArrayOutputStream

class RichInputWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var onCommitImage: ((uri: Uri, mime: String?) -> Unit)? = null
    var onPasteImage: ((dataUrl: String) -> Unit)? = null

    init {
        // Enable clipboard monitoring for paste events
        setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                checkAndHandleClipboardImage()
            }
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val baseInputConnection = super.onCreateInputConnection(outAttrs)
        if (baseInputConnection == null) {
            return null
        }
        
        EditorInfoCompat.setContentMimeTypes(
            outAttrs,
            arrayOf("image/*", "image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml")
        )
        
        // Wrap the base connection to intercept paste events
        val wrappedConnection = object : InputConnection by baseInputConnection {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Check if this might be a paste operation
                if (text != null && text.length > 1) {
                    post { checkAndHandleClipboardImage() }
                }
                return baseInputConnection.commitText(text, newCursorPosition)
            }
            
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                // Intercept Ctrl+V or Cmd+V
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    val isCtrlPressed = event.isCtrlPressed || event.isMetaPressed
                    if (isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_V) {
                        post { checkAndHandleClipboardImage() }
                    }
                }
                return baseInputConnection.sendKeyEvent(event)
            }
        }
        
        val callback = InputConnectionCompat.OnCommitContentListener { contentInfo: InputContentInfoCompat, flags: Int, opts ->
            try {
                if ((flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                    contentInfo.requestPermission()
                }
                val desc: ClipDescription = contentInfo.description
                val mime = if (desc.mimeTypeCount > 0) desc.getMimeType(0) else null
                onCommitImage?.invoke(contentInfo.contentUri, mime)
                true
            } catch (t: Throwable) {
                false
            }
        }
        
        return InputConnectionCompat.createWrapper(wrappedConnection, outAttrs, callback)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Detect paste keyboard shortcut
        if (event != null) {
            val isCtrlPressed = event.isCtrlPressed || event.isMetaPressed
            if (isCtrlPressed && keyCode == KeyEvent.KEYCODE_V) {
                // Schedule clipboard check after the paste event processes
                postDelayed({ checkAndHandleClipboardImage() }, 100)
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun checkAndHandleClipboardImage() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip ?: return
            
            if (clipData.itemCount > 0) {
                val item = clipData.getItemAt(0)
                
                // Check for image URI
                val uri = item.uri
                if (uri != null && isImageUri(uri)) {
                    handleImageUri(uri)
                    return
                }
                
                // On Android 12+, check for images in HTML clipboard content
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val htmlText = item.htmlText
                    if (!htmlText.isNullOrEmpty() && htmlText.contains("data:image", ignoreCase = true)) {
                        // Extract data URLs from HTML
                        val dataUrlRegex = """data:image/[^;]+;base64,[^"'\s]+""".toRegex()
                        dataUrlRegex.find(htmlText)?.let { match ->
                            insertImageViaJavaScript(match.value)
                            onPasteImage?.invoke(match.value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RichInputWebView", "Error handling clipboard", e)
        }
    }

    private fun isImageUri(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.startsWith("image/") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun handleImageUri(uri: Uri) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                // Determine MIME type
                val mimeType = contentResolver.getType(uri) ?: "image/png"
                
                // Decode and optionally downscale the image
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    val maxDimension = 3000
                    val ratio = minOf(
                        maxDimension.toFloat() / bitmap.width,
                        maxDimension.toFloat() / bitmap.height,
                        1f
                    )
                    
                    val scaledBitmap = if (ratio < 1f) {
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * ratio).toInt(),
                            (bitmap.height * ratio).toInt(),
                            true
                        )
                    } else {
                        bitmap
                    }
                    
                    // Convert to data URL
                    val outputStream = ByteArrayOutputStream()
                    val format = if (mimeType.contains("jpeg", ignoreCase = true)) {
                        Bitmap.CompressFormat.JPEG
                    } else {
                        Bitmap.CompressFormat.PNG
                    }
                    scaledBitmap.compress(format, 90, outputStream)
                    val base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    val dataUrl = "data:$mimeType;base64,$base64String"
                    
                    // Insert the image via JavaScript
                    insertImageViaJavaScript(dataUrl)
                    
                    // Notify listener if set
                    onPasteImage?.invoke(dataUrl)
                    
                    // Clear the clipboard to prevent duplicate pastes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.clearPrimaryClip()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RichInputWebView", "Error handling image paste", e)
        }
    }

    private fun insertImageViaJavaScript(dataUrl: String) {
        post {
            val escapedDataUrl = dataUrl
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "")
            evaluateJavascript(
                """
                (function() {
                    var img = document.createElement('img');
                    img.src = '$escapedDataUrl';
                    img.style.maxWidth = '100%';
                    img.style.height = 'auto';
                    
                    var selection = window.getSelection();
                    var range;
                    
                    if (selection.rangeCount > 0) {
                        range = selection.getRangeAt(0);
                        range.deleteContents();
                        range.insertNode(img);
                        
                        // Move cursor after the image
                        range = document.createRange();
                        range.setStartAfter(img);
                        range.collapse(true);
                        selection.removeAllRanges();
                        selection.addRange(range);
                    } else {
                        // No selection, append to editor
                        var editor = document.getElementById('editor');
                        if (editor) {
                            editor.appendChild(img);
                        }
                    }
                    
                    // Trigger content change event
                    if(window.AndroidBridge && window.AndroidBridge.onContentChanged) {
                        setTimeout(function() {
                            window.AndroidBridge.onContentChanged(document.getElementById('editor').innerHTML);
                        }, 100);
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }
}