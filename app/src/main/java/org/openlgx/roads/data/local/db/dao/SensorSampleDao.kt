package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity

@Dao
interface SensorSampleDao {

    @Insert
    suspend fun insert(sample: SensorSampleEntity): Long

    @Insert
    suspend fun insertAll(samples: List<SensorSampleEntity>)

    @Query("SELECT COUNT(*) FROM sensor_samples")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM sensor_samples WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Long

    @Query(
        "SELECT MAX(wallClockUtcEpochMs) FROM sensor_samples WHERE sessionId = :sessionId",
    )
    suspend fun maxWallClockEpochMsForSession(sessionId: Long): Long?

    @Query(
        "SELECT MIN(wallClockUtcEpochMs) FROM sensor_samples WHERE sessionId = :sessionId",
    )
    suspend fun minWallClockForSession(sessionId: Long): Long?

    @Query(
        "SELECT wallClockUtcEpochMs FROM sensor_samples WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC",
    )
    suspend fun wallClocksAscForSession(sessionId: Long): List<Long>

    @Query(
        "SELECT * FROM sensor_samples WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageForSessionOrdered(
        sessionId: Long,
        limit: Int,
        offset: Int,
    ): List<SensorSampleEntity>

    @Query(
        "SELECT DISTINCT sensorType FROM sensor_samples WHERE sessionId = :sessionId ORDER BY sensorType",
    )
    suspend fun distinctSensorTypesForSession(sessionId: Long): List<Int>
}
