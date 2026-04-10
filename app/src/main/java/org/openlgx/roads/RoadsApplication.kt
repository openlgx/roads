package org.openlgx.roads

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.debug.AgentDebugLog
import org.openlgx.roads.di.HiltWorkerFactoryEntryPoint
import org.openlgx.roads.di.PassiveCollectionEntryPoint
import org.openlgx.roads.di.PilotBootstrapEntryPoint
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
            // #region agent log
            AgentDebugLog.install(this)
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
                AgentDebugLog.appendSync(
                    hypothesisId = "H_UC",
                    location = "RoadsApplication.kt:onCreate",
                    message = "defaultUncaughtExceptionHandler",
                    data =
                        mapOf(
                            "thread" to (thread?.name ?: "null"),
                            "ex" to exception.javaClass.simpleName,
                            "msg" to (exception.message ?: ""),
                        ),
                )
                prev?.uncaughtException(thread, exception)
            }
            AgentDebugLog.emit(
                hypothesisId = "H_APP",
                location = "RoadsApplication.kt:onCreate",
                message = "application_onCreate_start",
                data = emptyMap(),
            )
            // #endregion
        }

        EntryPointAccessors.fromApplication(this, PilotBootstrapEntryPoint::class.java)
            .pilotBootstrapCoordinator()
            .scheduleInitialRun()

        EntryPointAccessors.fromApplication(this, PassiveCollectionEntryPoint::class.java)
            .passiveCollectionHandle()
            .start()
    }
}
