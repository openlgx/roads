package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity

@Dao
interface LocationSampleDao {

    @Insert
    suspend fun insert(sample: LocationSampleEntity): Long

    @Insert
    suspend fun insertAll(samples: List<LocationSampleEntity>)

    @Query("SELECT COUNT(*) FROM location_samples")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM location_samples WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Long

    @Query(
        "SELECT * FROM location_samples WHERE sessionId = :sessionId ORDER BY wallClockUtcEpochMs DESC LIMIT 1",
    )
    suspend fun latestForSession(sessionId: Long): LocationSampleEntity?

    @Query(
        "SELECT MIN(wallClockUtcEpochMs) FROM location_samples WHERE sessionId = :sessionId",
    )
    suspend fun minWallClockForSession(sessionId: Long): Long?

    @Query(
        "SELECT wallClockUtcEpochMs FROM location_samples WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC",
    )
    suspend fun wallClocksAscForSession(sessionId: Long): List<Long>

    @Query(
        "SELECT * FROM location_samples WHERE sessionId = :sessionId " +
            "ORDER BY wallClockUtcEpochMs ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageForSessionOrdered(
        sessionId: Long,
        limit: Int,
        offset: Int,
    ): List<LocationSampleEntity>
}
