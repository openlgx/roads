package org.openlgx.roads.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.openlgx.roads.BuildConfig
import org.openlgx.roads.debug.AgentDebugLog
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object CoroutineDispatchersModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        // #region agent log
        val handler =
            CoroutineExceptionHandler { _, throwable ->
                if (BuildConfig.DEBUG) {
                    AgentDebugLog.emit(
                        hypothesisId = "H_COR",
                        location = "CoroutineDispatchersModule.kt:provideApplicationScope",
                        message = "ApplicationScope uncaught coroutine exception",
                        data =
                            mapOf(
                                "ex" to throwable.javaClass.simpleName,
                                "msg" to (throwable.message ?: ""),
                            ),
                    )
                }
                Timber.e(throwable, "ApplicationScope coroutine failed")
            }
        // #endregion
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
}
