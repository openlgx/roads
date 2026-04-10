package org.openlgx.roads.ui.review

import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TripReviewWebView(
    payloadBase64: String?,
    modifier: Modifier = Modifier,
    onOpenSessionFromMap: ((Long) -> Unit)? = null,
) {
    val html = remember { tripReviewHtmlDocument() }
    var lastLoadedPayload by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                if (onOpenSessionFromMap != null) {
                    addJavascriptInterface(
                        RoadsReviewJsBridge(onOpenSessionFromMap),
                        "RoadsAndroid",
                    )
                }
            }
        },
        update = { wv ->
            val p = payloadBase64 ?: return@AndroidView
            if (p == lastLoadedPayload) return@AndroidView
            lastLoadedPayload = p
            wv.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?,
                    ) {
                        view?.evaluateJavascript("init('$p')", null)
                    }
                }
            wv.loadDataWithBaseURL(
                "file:///android_asset/review/",
                html,
                "text/html",
                "utf-8",
                null,
            )
        },
    )
}
