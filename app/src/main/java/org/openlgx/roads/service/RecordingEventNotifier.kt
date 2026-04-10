package org.openlgx.roads.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.openlgx.roads.R

/**
 * One-shot heads-up notifications for recording start/stop (distinct from ongoing FGS notification).
 */
@Singleton
class RecordingEventNotifier
@Inject
constructor(
    @ApplicationContext private val context: Context,
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch =
                NotificationChannel(
                    CHANNEL_EVENTS_ID,
                    app.getString(R.string.recording_events_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = app.getString(R.string.recording_events_channel_description)
                    setSound(null, null)
                }
            manager.createNotificationChannel(ch)
        }
    }

    fun notifyRecordingStarted(sessionId: Long) {
        val n =
            NotificationCompat.Builder(app, CHANNEL_EVENTS_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(app.getString(R.string.recording_started_title))
                .setContentText(
                    app.getString(R.string.recording_started_body, sessionShortLabel(sessionId)),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFY_RECORDING_STARTED_BASE + (sessionId and 0xffff).toInt(), n)
    }

    fun notifyRecordingStopped(
        sessionId: Long,
        terminal: String,
    ) {
        val n =
            NotificationCompat.Builder(app, CHANNEL_EVENTS_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(app.getString(R.string.recording_stopped_title))
                .setContentText(
                    app.getString(
                        R.string.recording_stopped_body,
                        sessionShortLabel(sessionId),
                        terminal,
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        manager.notify(NOTIFY_RECORDING_STOPPED_BASE + (sessionId and 0xffff).toInt(), n)
    }

    private fun sessionShortLabel(sessionId: Long): String = " #$sessionId"

    private companion object {
        const val CHANNEL_EVENTS_ID = "roads_recording_events_v1"
        const val NOTIFY_RECORDING_STARTED_BASE: Int = 0xD200
        const val NOTIFY_RECORDING_STOPPED_BASE: Int = 0xD300
    }
}
