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
}
