package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.openlgx.roads.processing.calibration.SessionCalibrationHook
import org.openlgx.roads.processing.calibration.SessionCalibrationHookImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class CalibrationModule {

    @Binds
    @Singleton
    abstract fun bindSessionCalibrationHook(impl: SessionCalibrationHookImpl): SessionCalibrationHook
}
