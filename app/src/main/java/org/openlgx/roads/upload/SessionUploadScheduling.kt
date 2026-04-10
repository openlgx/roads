package org.openlgx.roads.upload

import org.openlgx.roads.data.local.settings.AppSettings

interface SessionUploadScheduling {
    suspend fun scheduleAfterSessionCompleted(sessionId: Long, settings: AppSettings)
}
