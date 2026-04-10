package org.openlgx.roads.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.bootstrap.PilotBootstrapCoordinator

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PilotBootstrapEntryPoint {
    fun pilotBootstrapCoordinator(): PilotBootstrapCoordinator
}
