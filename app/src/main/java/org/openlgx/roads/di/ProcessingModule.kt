package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.processing.ondevice.SessionProcessingScheduler
import org.openlgx.roads.processing.ondevice.SessionProcessingSchedulerImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class ProcessingModule {
    @Binds
    @Singleton
    abstract fun bindSessionProcessingScheduler(
        impl: SessionProcessingSchedulerImpl,
    ): SessionProcessingScheduler
}
