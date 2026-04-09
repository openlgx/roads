package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.DeviceProfileEntity

@Dao
interface DeviceProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: DeviceProfileEntity)

    @Query("SELECT COUNT(*) FROM device_profiles")
    suspend fun count(): Long
}
