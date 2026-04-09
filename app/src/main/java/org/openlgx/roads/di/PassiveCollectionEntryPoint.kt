package org.openlgx.roads.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.collector.PassiveCollectionHandle

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PassiveCollectionEntryPoint {
    fun passiveCollectionHandle(): PassiveCollectionHandle
}
