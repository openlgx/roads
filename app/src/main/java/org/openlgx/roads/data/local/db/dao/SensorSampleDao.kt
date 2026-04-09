package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity

@Dao
interface SensorSampleDao {

    @Insert
    suspend fun insert(sample: SensorSampleEntity): Long

    @Query("SELECT COUNT(*) FROM sensor_samples")
    suspend fun count(): Long
}
