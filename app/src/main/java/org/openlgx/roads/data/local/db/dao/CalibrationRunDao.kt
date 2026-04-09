package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.CalibrationRunEntity

@Dao
interface CalibrationRunDao {

    @Insert
    suspend fun insert(run: CalibrationRunEntity): Long

    @Query("SELECT COUNT(*) FROM calibration_runs")
    suspend fun count(): Long
}
