package org.openlgx.roads.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.activityrecognition.ActivityRecognitionGateway

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityRecognitionGatewayEntryPoint {
    fun activityRecognitionGateway(): ActivityRecognitionGateway
}
