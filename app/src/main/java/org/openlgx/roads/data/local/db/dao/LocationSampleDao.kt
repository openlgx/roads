package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity

@Dao
interface LocationSampleDao {

    @Insert
    suspend fun insert(sample: LocationSampleEntity): Long

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun count(): Long
}
