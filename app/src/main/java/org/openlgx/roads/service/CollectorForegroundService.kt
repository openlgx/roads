package org.openlgx.roads.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.openlgx.roads.R
import org.openlgx.roads.collector.lifecycle.CollectorLifecycleState
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.sensor.SensorRecordingController

@AndroidEntryPoint
class CollectorForegroundService : Service() {

    @Inject lateinit var stateRegistry: CollectorServiceStateRegistry

    @Inject lateinit var locationRecordingController: LocationRecordingController

    @Inject lateinit var sensorRecordingController: SensorRecordingController

    @Inject lateinit var foregroundPresentationRegistry: CollectorForegroundPresentationRegistry

    private val job = SupervisorJob()
    private val serviceScope = CoroutineScope(job + Dispatchers.Default)
    private var presentationCollectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        stateRegistry.setForegroundRunning(true)
    }

    override fun onDestroy() {
        presentationCollectJob?.cancel()
        job.cancel()
        runBlocking(Dispatchers.IO) {
            coroutineScope {
                launch { locationRecordingController.stopAndFlush() }
                launch { sensorRecordingController.stopAndFlush() }
            }
        }
        stateRegistry.setForegroundRunning(false)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                presentationCollectJob?.cancel()
                runBlocking(Dispatchers.IO) {
                    coroutineScope {
                        launch { locationRecordingController.stopAndFlush() }
                        launch { sensorRecordingController.stopAndFlush() }
                    }
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
                if (sessionId <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                presentationCollectJob?.cancel()
                presentationCollectJob =
                    serviceScope.launch {
                        foregroundPresentationRegistry.state.collectLatest { pres ->
                            val n = buildNotification(sessionId, pres)
                            startForeground(NOTIFICATION_ID, n)
                        }
                    }

                val notification = buildNotification(sessionId, foregroundPresentationRegistry.state.value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                locationRecordingController.startRecording(sessionId)
                sensorRecordingController.startRecording(sessionId)
            }
        }
        return START_STICKY
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val fg =
            NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                getString(R.string.collector_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.collector_notification_channel_description)
            }
        manager.createNotificationChannel(fg)
    }

    private fun buildNotification(
        activeSessionId: Long,
        pres: CollectorForegroundPresentation?,
    ): Notification {
        ensureChannels()
        val (title, body) =
            when {
                pres == null ->
                    getString(R.string.collector_notification_title) to
                        getString(R.string.collector_notification_body_location)
                pres.lifecycleState == CollectorLifecycleState.ARMING ->
                    getString(R.string.collector_notification_title) to
                        getString(R.string.collector_notification_body_arming)
                pres.lifecycleState == CollectorLifecycleState.STOP_HOLD ->
                    getString(R.string.collector_notification_title) to
                        getString(
                            R.string.collector_notification_body_stop_hold,
                            pres.sessionShortLabel,
                        )
                else ->
                    getString(R.string.collector_notification_title) to
                        getString(
                            R.string.collector_notification_body_recording,
                            pres.sessionShortLabel,
                        )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START: String = "org.openlgx.roads.action.COLLECTOR_START"
        const val ACTION_STOP: String = "org.openlgx.roads.action.COLLECTOR_STOP"
        const val EXTRA_SESSION_ID: String = "extra_session_id"

        private const val CHANNEL_ID_FOREGROUND: String = "roads_collector_phase2b1"
        private const val NOTIFICATION_ID: Int = 0xC010
    }
}
