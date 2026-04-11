package org.openlgx.roads.activityrecognition

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import org.openlgx.roads.di.ActivityRecognitionGatewayEntryPoint
import org.openlgx.roads.di.PassiveCollectionEntryPoint

class ActivityRecognitionUpdatesReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val app = context.applicationContext as Application
        // Cold process: ensure coordinator mailbox is running before ingesting Play Services delivery.
        EntryPointAccessors.fromApplication(app, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
            .start()
        val gateway =
            EntryPointAccessors.fromApplication(app, ActivityRecognitionGatewayEntryPoint::class.java)
                .activityRecognitionGateway()
        gateway.ingestActivityRecognitionIntent(intent)
    }
}
