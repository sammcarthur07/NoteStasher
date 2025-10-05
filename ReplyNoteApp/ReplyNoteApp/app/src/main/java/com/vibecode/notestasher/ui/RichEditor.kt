package com.vibecode.notestasher.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichEditor(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    initialHtml: String,
    onHtmlChanged: (String) -> Unit,
    onImeImage: (android.net.Uri, String?) -> Unit,
    provideWebView: (WebView) -> Unit
) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webView?.destroy()
            } catch (t: Throwable) {
                // ignore
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            RichInputWebView(context).apply {
                webView = this
                provideWebView(this)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                
                // Enable clipboard access
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = false
                }
                
                @Suppress("DEPRECATION")
                run {
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true
                }
                
                // Request focus to ensure clipboard access
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = false
                scrollBarStyle = WebView.SCROLLBARS_INSIDE_OVERLAY
                setScrollbarFadingEnabled(false)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Set initial content after page loaded
                        val safe = initialHtml.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        evaluateJavascript("window.__setContent(\"$safe\");") { }
                    }
                }

                this.onCommitImage = { uri, mime ->
                    onImeImage(uri, mime)
                }
                
                // Handle pasted images (from clipboard)
                this.onPasteImage = { dataUrl ->
                    // Image has already been inserted via JavaScript in RichInputWebView
                    // Just trigger the content changed callback
                    post {
                        evaluateJavascript("if(window.AndroidBridge && window.AndroidBridge.onContentChanged) { window.AndroidBridge.onContentChanged(document.getElementById('editor').innerHTML); }", null)
                    }
                }

                addJavascriptInterface(object {
                    private var lastSent = ""
                    private var debounceRunnable: Runnable? = null
                    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    @JavascriptInterface
                    fun onContentChanged(html: String) {
                        // Debounce callback to Compose side
                        debounceRunnable?.let { handler.removeCallbacks(it) }
                        val r = Runnable {
                            if (html != lastSent) {
                                lastSent = html
                                onHtmlChanged(html)
                            }
                        }
                        debounceRunnable = r
                        handler.postDelayed(r, 750)
                    }
                    
                    @JavascriptInterface
                    fun insertImageFromNative(dataUrl: String) {
                        // This method can be called from native code to insert images
                        post {
                            val escapedUrl = dataUrl.replace("\\", "\\\\").replace("'", "\\'")
                            evaluateJavascript("document.execCommand('insertImage', false, '$escapedUrl');", null)
                        }
                    }
                }, "AndroidBridge")

                loadDataWithBaseURL(
                    null,
                    EDITOR_HTML,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        },
        update = { view ->
            // no-op
        }
    )
}

private const val EDITOR_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />
  <style>
    html, body { margin:0; padding:0; background: transparent; color: #fff; font-family: sans-serif; }
    #editor { min-height: 220px; outline: none; padding: 12px; border: 1px solid #98FB98; border-radius: 8px; background: transparent; overflow-y: auto; max-height: 100vh; box-sizing: border-box; }
    a { color: #64B5F6; }
    img { max-width: 100%; height: auto; }
  </style>
  <script>
    function debounce(fn, delay){
      let t; return function(){ clearTimeout(t); t = setTimeout(()=>fn.apply(this, arguments), delay); };
    }
    window.__setContent = function(html){
      const ed = document.getElementById('editor');
      ed.innerHTML = html || '';
    };
    window.__getHtml = function(){
      return document.getElementById('editor').innerHTML || '';
    };
    function getComputedLineHeight(el){
      const lh = window.getComputedStyle(el).lineHeight;
      return lh;
    }
    function collectInline(node, active){
      active = active || { bold:false, italic:false, underline:false, color:null, fontSize:null, fontFamily:null, link:null };
      let res = { text: '', spans: [] };
      function pushSpan(start, end, styles){ if(end>start){ res.spans.push({start:start,end:end,styles:styles}); } }
      function walk(n, styles){
        if(n.nodeType===Node.TEXT_NODE){
          const t = n.nodeValue || '';
          const start = res.text.length;
          res.text += t;
          const end = res.text.length;
          pushSpan(start,end,styles);
        } else if(n.nodeType===Node.ELEMENT_NODE){
          let s = Object.assign({}, styles);
          const tag = n.tagName.toLowerCase();
          if(tag==='b' || tag==='strong'){ s.bold = true; }
          if(tag==='i' || tag==='em'){ s.italic = true; }
          if(tag==='u'){ s.underline = true; }
          if(tag==='a' && n.getAttribute('href')){ s.link = n.getAttribute('href'); }
          const style = n.getAttribute('style') || '';
          const colorMatch = style.match(/color:\s*([^;]+)/i); if(colorMatch){ s.color = colorMatch[1].trim(); }
          const sizeMatch = style.match(/font-size:\s*([0-9.]+)(px|pt|em|rem)/i); if(sizeMatch){ s.fontSize = sizeMatch[1]+sizeMatch[2]; }
          const famMatch = style.match(/font-family:\s*([^;]+)/i); if(famMatch){ s.fontFamily = famMatch[1].trim(); }
          if(tag==='img'){
            // Skip inline images here; handled at block level
          } else {
            for(let i=0;i<n.childNodes.length;i++) walk(n.childNodes[i], s);
          }
        }
      }
      walk(node, active);
      return res;
    }
    function serializeImage(el){
      const MAX = 3000;
      const img = el;
      const iw = img.naturalWidth || img.width || 0;
      const ih = img.naturalHeight || img.height || 0;
      try {
        if (iw>0 && ih>0 && (iw>MAX || ih>MAX)){
          const ratio = Math.min(MAX/iw, MAX/ih);
          const w = Math.round(iw*ratio), h = Math.round(ih*ratio);
          const canvas = document.createElement('canvas');
          canvas.width = w; canvas.height = h;
          const ctx = canvas.getContext('2d');
          ctx.drawImage(img, 0, 0, w, h);
          const dataUrl = canvas.toDataURL('image/png');
          return { type:'image', src:dataUrl, width:w, height:h };
        }
      } catch(e) {
        // CORS-tainted canvas; fall back to src URL
      }
      return { type:'image', src: img.getAttribute('src')||'', width: iw||parseInt(img.getAttribute('width')||'0'), height: ih||parseInt(img.getAttribute('height')||'0') };
    }
    window.__extractBlocksJSON = function(){
      const ed = document.getElementById('editor');
      const blocks = [];
      function handleBlock(el, type){
        if(type==='ul' || type==='ol'){
          const items = el.querySelectorAll('li');
          items.forEach(li=>{
            const c = collectInline(li);
            blocks.push({type:'list_item', listType:type, text:c.text, spans:c.spans||[], lineHeight:getComputedLineHeight(li)});
          });
        } else if(type==='image'){
          blocks.push(serializeImage(el));
        } else {
          // Split images and text in order
          let tmp = document.createElement('span');
          let hadParts = false;
          for (let i=0;i<el.childNodes.length;i++){
            const cn = el.childNodes[i];
            if (cn.nodeType===Node.ELEMENT_NODE && cn.tagName.toLowerCase()==='img'){
              // flush text collected so far
              if (tmp.childNodes.length>0){
                const c1 = collectInline(tmp);
                blocks.push({type:type, text:c1.text, spans:c1.spans||[], lineHeight:getComputedLineHeight(el)});
                tmp = document.createElement('span');
                hadParts = true;
              }
              blocks.push(serializeImage(cn));
              hadParts = true;
            } else {
              tmp.appendChild(cn.cloneNode(true));
            }
          }
          if (tmp.childNodes.length>0 || !hadParts){
            const c2 = collectInline(tmp.childNodes.length>0 ? tmp : el);
            blocks.push({type:type, text:c2.text, spans:c2.spans||[], lineHeight:getComputedLineHeight(el)});
          }
        }
      }
      for(let i=0;i<ed.childNodes.length;i++){
        const n = ed.childNodes[i];
        if(n.nodeType===Node.ELEMENT_NODE){
          const tag = n.tagName.toLowerCase();
          if(tag==='h1'||tag==='h2'||tag==='h3'||tag==='h4'||tag==='h5'||tag==='h6'){
            handleBlock(n, tag);
          } else if(tag==='p' || tag==='div'){
            // treat div as paragraph
            handleBlock(n, 'p');
          } else if(tag==='ul' || tag==='ol'){
            handleBlock(n, tag);
          } else if(tag==='img'){
            handleBlock(n, 'image');
          } else {
            // fallback: serialize as paragraph
            handleBlock(n, 'p');
          }
        } else if(n.nodeType===Node.TEXT_NODE){
          const t = (n.nodeValue||'').trim();
          if(t.length>0){ blocks.push({type:'p', text:t, spans:[{start:0,end:t.length,styles:{}}], lineHeight:null}); }
        }
      }
      return JSON.stringify({format:'blocks_v1', blocks:blocks});
    };
    document.addEventListener('DOMContentLoaded', function(){
      const ed = document.createElement('div');
      ed.id = 'editor';
      ed.contentEditable = 'true';
      document.body.appendChild(ed);
      const notify = debounce(function(){
        if(window.AndroidBridge && window.AndroidBridge.onContentChanged){
          AndroidBridge.onContentChanged(window.__getHtml());
        }
      }, 200);
      ed.addEventListener('input', notify);
      // Enhanced paste handling with fallback to native Android handling
      ed.addEventListener('paste', async function(e){
        try {
          const cd = e.clipboardData;
          if (cd && cd.items) {
            for (let i=0;i<cd.items.length;i++) {
              const it = cd.items[i];
              if (it.type && it.type.indexOf('image')===0) {
                e.preventDefault();
                const file = it.getAsFile();
                if (file) {
                  const reader = new FileReader();
                  reader.onload = function(){
                    const img = new Image();
                    img.onload = function(){
                      // downscale if needed
                      const MAX = 3000; let w = img.naturalWidth, h = img.naturalHeight;
                      if (w>MAX || h>MAX) { const r = Math.min(MAX/w, MAX/h); w=Math.round(w*r); h=Math.round(h*r); }
                      const canvas = document.createElement('canvas'); canvas.width=w; canvas.height=h;
                      const ctx = canvas.getContext('2d'); ctx.drawImage(img,0,0,w,h);
                      const dataUrl = canvas.toDataURL('image/png');
                      document.execCommand('insertImage', false, dataUrl);
                      notify();
                    };
                    img.src = reader.result;
                  };
                  reader.readAsDataURL(file);
                  return;
                }
              }
            }
          }
          // Try async clipboard API as fallback
          if (navigator.clipboard && navigator.clipboard.read) {
            try {
              const items = await navigator.clipboard.read();
              for (const item of items) {
                for (const type of item.types) {
                  if (type.startsWith('image/')) {
                    e.preventDefault();
                    const blob = await item.getType(type);
                    const reader = new FileReader();
                    reader.onload = function(){
                      document.execCommand('insertImage', false, reader.result);
                      notify();
                    };
                    reader.readAsDataURL(blob);
                    return;
                  }
                }
              }
            } catch(err) {
              console.log('Clipboard API not available or permission denied');
            }
          }
          // Fallback: insert HTML that may include <img src="...">
          if (cd) {
            const getData = cd.getData ? (t)=>cd.getData(t) : ()=>null;
            const html = getData('text/html') || '';
            if (html && /<img\s/i.test(html)) {
              e.preventDefault();
              const safe = html.replace(/<\/(?:script|style|iframe)>/gi,'')
                               .replace(/<(script|style|iframe)[^>]*?>[\s\S]*?<\/\1>/gi,'');
              document.execCommand('insertHTML', false, safe);
              notify();
              return;
            }
            // URL fallback
            const uri = getData('text/uri-list') || getData('text/plain') || '';
            const m = uri.match(/https?:\/\/\S+\.(png|jpe?g|gif|webp)(\?\S*)?$/i);
            if (m) {
              e.preventDefault();
              document.execCommand('insertImage', false, m[0]);
              notify();
              return;
            }
          }
        } catch(err) {
          console.log('Paste error:', err);
        }
        setTimeout(notify, 100);
      });
    });
  </script>
  </head>
  <body></body>
  </html>
"""
