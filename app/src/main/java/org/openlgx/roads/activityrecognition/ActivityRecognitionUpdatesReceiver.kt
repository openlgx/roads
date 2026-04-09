package org.openlgx.roads.activityrecognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ActivityRecognitionUpdatesReceiver : BroadcastReceiver() {

    @Inject lateinit var gateway: ActivityRecognitionGateway

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        gateway.ingestActivityRecognitionIntent(intent)
    }
}
