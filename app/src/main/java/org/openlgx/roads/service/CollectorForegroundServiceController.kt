package org.openlgx.roads.service

interface CollectorForegroundServiceController {
    fun startCollectorService(sessionId: Long)

    fun stopCollectorService()
}
