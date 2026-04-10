package org.openlgx.roads.ui.navigation

object Routes {
    const val Home = "home"
    const val Settings = "settings"
    const val Diagnostics = "diagnostics"
    const val SessionList = "sessions"

    const val SessionDetailBase = "session"
    const val SessionDetail = "session/{sessionId}"
    fun sessionDetail(sessionId: Long): String = "session/$sessionId"

    const val SessionReview = "session/{sessionId}/review"
    fun sessionReview(sessionId: Long): String = "session/$sessionId/review"

    const val AllRunsReview = "review/all-runs"
}
