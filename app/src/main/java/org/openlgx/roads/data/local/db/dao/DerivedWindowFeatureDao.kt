package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity

@Dao
interface DerivedWindowFeatureDao {

    @Insert
    suspend fun insert(row: DerivedWindowFeatureEntity): Long

    @Query("SELECT COUNT(*) FROM derived_window_features")
    suspend fun count(): Long
}
