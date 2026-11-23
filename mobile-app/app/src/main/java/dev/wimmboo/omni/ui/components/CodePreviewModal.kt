package dev.wimmboo.omni.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wimmboo.omni.ui.CodePreviewState
import dev.wimmboo.omni.ui.icons.OmniIcons
import dev.wimmboo.omni.ui.theme.InterFontFamily
import dev.wimmboo.omni.ui.theme.OmniTheme

/**
 * Sandboxed live HTML/SVG/XML/JS code preview modal mirroring #codePreviewModal in index.html.
 * This is the ONLY WebView in the entire app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodePreviewModal(
    preview: CodePreviewState,
    onDismiss: () -> Unit,
) {
    val colors = OmniTheme.colors
    val context = LocalContext.current
    val htmlDocument = remember(preview.code, preview.language) {
        preparePreviewDocument(preview.code, preview.language)
    }
    var webViewReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        webViewReady = true
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x8C000000))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.bgModal)
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Live Code Preview",
                        color = colors.text,
                        fontFamily = InterFontFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OmniIconButton(
                        icon = OmniIcons.Close,
                        contentDescription = "Close preview",
                        tint = colors.textMuted,
                        onClick = onDismiss,
                    )
                }

                if (webViewReady) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                setBackgroundColor(android.graphics.Color.WHITE)
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = false
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = false
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    @Suppress("DEPRECATION")
                                    allowFileAccessFromFileURLs = false
                                    @Suppress("DEPRECATION")
                                    allowUniversalAccessFromFileURLs = false
                                    cacheMode = WebSettings.LOAD_NO_CACHE
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                }
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val url = request?.url ?: return true
                                        val scheme = url.scheme?.lowercase()
                                        if (scheme == "http" || scheme == "https") {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                                            } catch (_: Exception) {
                                            }
                                        }
                                        return true
                                    }
                                }
                                tag = htmlDocument
                                loadDataWithBaseURL(
                                    "about:blank",
                                    htmlDocument,
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        },
                        update = { webView ->
                            if (webView.tag != htmlDocument) {
                                webView.tag = htmlDocument
                                webView.loadDataWithBaseURL(
                                    "about:blank",
                                    htmlDocument,
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        },
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.destroy()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

private fun preparePreviewDocument(code: String, language: String): String {
    val trimmed = code.trim()
    val lang = language.lowercase().trim()
    if (lang == "svg" || (trimmed.startsWith("<svg", ignoreCase = true) && !trimmed.contains("<html", ignoreCase = true))) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1.0" />
              <style>
                html, body { margin: 0; padding: 16px; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #ffffff; box-sizing: border-box; }
                svg { max-width: 100%; height: auto; }
              </style>
            </head>
            <body>$code</body>
            </html>
        """.trimIndent()
    }
    return code
}
