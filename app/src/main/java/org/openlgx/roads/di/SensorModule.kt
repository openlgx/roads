package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.sensor.SensorCaptureConfig
import org.openlgx.roads.sensor.SensorGateway
import org.openlgx.roads.sensor.SensorRecordingController
import org.openlgx.roads.sensor.SessionSensorRecorder
import org.openlgx.roads.sensor.SystemSensorGateway

@Module
@InstallIn(SingletonComponent::class)
abstract class SensorBindModule {

    @Binds @Singleton
    abstract fun bindSensorGateway(impl: SystemSensorGateway): SensorGateway

    @Binds @Singleton
    abstract fun bindSensorRecordingController(
        impl: SessionSensorRecorder,
    ): SensorRecordingController
}

@Module
@InstallIn(SingletonComponent::class)
object SensorProvideModule {

    @Provides @Singleton
    fun provideSensorCaptureConfig(): SensorCaptureConfig = SensorCaptureConfig()
}
