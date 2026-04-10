package org.openlgx.roads.receiver

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import org.openlgx.roads.di.PassiveCollectionEntryPoint

/**
 * Uses an entry point so this receiver never depends on Hilt field injection (avoids lateinit crashes
 * if the receiver runs in an unusual process/ordering path).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as Application
        EntryPointAccessors.fromApplication(app, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
            .start()
    }
}
