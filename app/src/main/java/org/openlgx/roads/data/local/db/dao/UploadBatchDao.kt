package org.openlgx.roads.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity

@Dao
interface UploadBatchDao {

    @Insert
    suspend fun insert(batch: UploadBatchEntity): Long

    @Update
    suspend fun update(batch: UploadBatchEntity)

    @Query("SELECT * FROM upload_batches WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UploadBatchEntity?

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

    @Query(
        """
        SELECT * FROM upload_batches
        WHERE sessionId = :sessionId AND batchUploadState = 'ACKED'
        ORDER BY createdAtEpochMs DESC LIMIT 1
        """,
    )
    suspend fun findAckedBatchForSession(sessionId: Long): UploadBatchEntity?

    /**
     * In-flight upload staging: prepared ZIP recorded locally, upload not yet ACKed.
     * Used to skip re-export on WorkManager retry after network blips.
     */
    @Query(
        """
        SELECT * FROM upload_batches
        WHERE sessionId = :sessionId
          AND batchUploadState IN ('READY','FAILED_RETRYABLE')
          AND contentChecksumSha256 IS NOT NULL
        ORDER BY id DESC
        LIMIT 1
        """,
    )
    suspend fun findStagingBatchForSession(sessionId: Long): UploadBatchEntity?

    @Query(
        """
        DELETE FROM upload_batches
        WHERE sessionId = :sessionId
          AND batchUploadState IN ('READY','FAILED_RETRYABLE')
        """,
    )
    suspend fun deleteStagingBatchesForSession(sessionId: Long)
}
