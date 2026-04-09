package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.location.ArmingDrivingGate
import org.openlgx.roads.location.DefaultArmingDrivingGate
import org.openlgx.roads.location.FusedLocationGateway
import org.openlgx.roads.location.LocationCaptureConfig
import org.openlgx.roads.location.LocationGateway
import org.openlgx.roads.location.LocationRecordingController
import org.openlgx.roads.location.SessionLocationRecorder

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationBindModule {

    @Binds
    @Singleton
    abstract fun bindLocationGateway(impl: FusedLocationGateway): LocationGateway

    @Binds
    @Singleton
    abstract fun bindArmingDrivingGate(impl: DefaultArmingDrivingGate): ArmingDrivingGate

    @Binds
    @Singleton
    abstract fun bindLocationRecordingController(
        impl: SessionLocationRecorder,
    ): LocationRecordingController
}

@Module
@InstallIn(SingletonComponent::class)
object LocationProvideModule {

    @Provides
    @Singleton
    fun provideLocationCaptureConfig(): LocationCaptureConfig = LocationCaptureConfig()
}
