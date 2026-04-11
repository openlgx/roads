package org.openlgx.roads.receiver

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import org.openlgx.roads.di.PassiveCollectionEntryPoint
import org.openlgx.roads.di.PassiveKeepaliveEntryPoint

/**
 * After an app update, re-bootstrap passive collection and keepalive (same as cold start).
 */
class AppUpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as Application
        EntryPointAccessors.fromApplication(app, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
            .start()
        EntryPointAccessors.fromApplication(app, PassiveKeepaliveEntryPoint::class.java)
            .passiveKeepaliveScheduler()
            .ensureScheduled()
    }
}
