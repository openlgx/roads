package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity

@Dao
interface UploadBatchDao {

    @Insert
    suspend fun insert(batch: UploadBatchEntity): Long

    @Query("SELECT COUNT(*) FROM upload_batches")
    suspend fun count(): Long

    @Query(
        """
        SELECT COUNT(*) FROM upload_batches
        WHERE batchUploadState IN ('PENDING_POLICY','READY','FAILED_RETRYABLE')
        """,
    )
    suspend fun countPendingOrRetryable(): Long

    @Query("SELECT MAX(lastSuccessfulUploadAtEpochMs) FROM upload_batches")
    suspend fun latestSuccessfulUploadAt(): Long?

    @Query(
        """
        SELECT * FROM upload_batches WHERE sessionId = :sessionId
        ORDER BY createdAtEpochMs DESC LIMIT 1
        """,
    )
    suspend fun latestForSession(sessionId: Long): UploadBatchEntity?

    @Query(
        """
        SELECT MAX(createdAtEpochMs) FROM upload_batches
        WHERE batchUploadState IN ('UPLOADING','FAILED_RETRYABLE','FAILED_PERMANENT','ACKED')
        """,
    )
    suspend fun latestUploadAttemptAt(): Long?

    @Query(
        """
        SELECT * FROM upload_batches ORDER BY createdAtEpochMs DESC LIMIT 1
        """,
    )
    suspend fun latestBatchOverall(): UploadBatchEntity?
}
