package org.openlgx.roads.activityrecognition

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import org.openlgx.roads.di.ActivityRecognitionGatewayEntryPoint

class ActivityRecognitionUpdatesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val app = context.applicationContext as Application
        val gateway =
            EntryPointAccessors.fromApplication(app, ActivityRecognitionGatewayEntryPoint::class.java)
                .activityRecognitionGateway()
        gateway.ingestActivityRecognitionIntent(intent)
    }
}
