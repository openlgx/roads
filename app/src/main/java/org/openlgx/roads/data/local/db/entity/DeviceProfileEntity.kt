package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "device_profiles",
    indices = [Index(value = ["installUuid"], unique = true)],
)
data class DeviceProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val installUuid: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val sdkInt: Int,
    val sensorCapabilitiesJson: String? = null,
    val normalizationParamsJson: String? = null,
    val lastProfiledAtEpochMs: Long? = null,
)
