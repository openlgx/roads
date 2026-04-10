package org.openlgx.roads.upload

import org.openlgx.roads.data.local.settings.AppSettings

fun interface SessionUploadScheduling {
    fun scheduleAfterSessionCompleted(sessionId: Long, settings: AppSettings)
}
