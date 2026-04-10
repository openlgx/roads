package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.upload.SessionUploadScheduling
import org.openlgx.roads.upload.SessionUploadWorkScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds
    @Singleton
    abstract fun bindSessionUploadScheduling(
        impl: SessionUploadWorkScheduler,
    ): SessionUploadScheduling
}
