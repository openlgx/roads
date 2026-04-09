package org.openlgx.roads

import android.app.Application
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import org.openlgx.roads.di.PassiveCollectionEntryPoint
import timber.log.Timber

@HiltAndroidApp
class RoadsApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        EntryPointAccessors.fromApplication(this, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
            .start()
    }
}
