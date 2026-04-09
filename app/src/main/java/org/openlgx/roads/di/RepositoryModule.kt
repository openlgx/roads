package org.openlgx.roads.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.data.repo.DiagnosticsRepository
import org.openlgx.roads.data.repo.DiagnosticsRepositoryImpl
import org.openlgx.roads.data.local.settings.AppSettingsRepository
import org.openlgx.roads.data.local.settings.AppSettingsRepositoryImpl
import org.openlgx.roads.data.repo.session.SessionInspectionRepository
import org.openlgx.roads.data.repo.session.SessionInspectionRepositoryImpl
import org.openlgx.roads.export.ExportDiagnosticsRepository
import org.openlgx.roads.export.ExportDiagnosticsRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(impl: AppSettingsRepositoryImpl): AppSettingsRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository

    @Binds
    @Singleton
    abstract fun bindSessionInspectionRepository(
        impl: SessionInspectionRepositoryImpl,
    ): SessionInspectionRepository

    @Binds
    @Singleton
    abstract fun bindExportDiagnosticsRepository(
        impl: ExportDiagnosticsRepositoryImpl,
    ): ExportDiagnosticsRepository
}
