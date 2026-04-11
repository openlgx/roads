package org.openlgx.roads.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.collector.PassiveKeepaliveScheduler

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PassiveKeepaliveEntryPoint {
    fun passiveKeepaliveScheduler(): PassiveKeepaliveScheduler
}
