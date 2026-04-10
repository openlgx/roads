package org.openlgx.roads

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import org.openlgx.roads.di.HiltWorkerFactoryEntryPoint
import org.openlgx.roads.di.PassiveCollectionEntryPoint
import timber.log.Timber

@HiltAndroidApp
class RoadsApplication : Application(), Configuration.Provider {

    /**
     * WorkManager can call [workManagerConfiguration] before field injection on [Application] runs.
     * Resolve the factory via an entry point and cache a single [Configuration] instance.
     */
    private val workManagerConfig: Configuration by lazy {
        val factory = EntryPointAccessors.fromApplication(this, HiltWorkerFactoryEntryPoint::class.java)
            .hiltWorkerFactory()
        Configuration.Builder().setWorkerFactory(factory).build()
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfig

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
