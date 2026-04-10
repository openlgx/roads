package org.openlgx.roads

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import org.openlgx.roads.di.PassiveCollectionEntryPoint
import timber.log.Timber

@HiltAndroidApp
class RoadsApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

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
