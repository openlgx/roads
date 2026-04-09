package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.activityrecognition.ActivityRecognitionGateway
import org.openlgx.roads.activityrecognition.ActivityRecognitionGatewayImpl
import org.openlgx.roads.collector.PassiveCollectionCoordinator
import org.openlgx.roads.collector.PassiveCollectionHandle
import org.openlgx.roads.data.repo.RecordingSessionRepository
import org.openlgx.roads.data.repo.RecordingSessionRepositoryImpl
import org.openlgx.roads.service.CollectorForegroundServiceController
import org.openlgx.roads.service.CollectorForegroundServiceControllerImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class CollectorBindModule {

    @Binds @Singleton
    abstract fun bindPassiveCollectionHandle(
        impl: PassiveCollectionCoordinator,
    ): PassiveCollectionHandle

    @Binds @Singleton
    abstract fun bindActivityRecognitionGateway(
        impl: ActivityRecognitionGatewayImpl,
    ): ActivityRecognitionGateway

    @Binds @Singleton
    abstract fun bindRecordingSessionRepository(
        impl: RecordingSessionRepositoryImpl,
    ): RecordingSessionRepository

    @Binds @Singleton
    abstract fun bindCollectorForegroundServiceController(
        impl: CollectorForegroundServiceControllerImpl,
    ): CollectorForegroundServiceController
}
