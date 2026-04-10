package org.openlgx.roads.ui.review

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

class RoadsReviewJsBridge(
    private val onOpenSession: (Long) -> Unit,
) {
    @JavascriptInterface
    fun openSession(sessionIdStr: String?) {
        val id = sessionIdStr?.toLongOrNull() ?: return
        Handler(Looper.getMainLooper()).post { onOpenSession(id) }
    }
}
