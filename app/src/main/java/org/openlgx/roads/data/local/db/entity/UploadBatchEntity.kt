package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.BatchUploadState

@Entity(
    tableName = "upload_batches",
    foreignKeys = [
        ForeignKey(
            entity = RecordingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sessionId"), Index("batchUploadState")],
)
data class UploadBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val createdAtEpochMs: Long,
    val payloadManifestJson: String,
    val batchUploadState: BatchUploadState,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long? = null,
    val lastErrorCategory: String? = null,
    val lastSuccessfulUploadAtEpochMs: Long? = null,
    val networkPolicySnapshotJson: String? = null,
    val localRawArtifactUri: String? = null,
    val localFilteredArtifactUri: String? = null,
    val remoteUploadJobId: String? = null,
    val remoteStorageKey: String? = null,
    val roadFilterSummaryJson: String? = null,
    /** Redundant copy of [RecordingSessionEntity.uuid] for traceability in queue rows. */
    val sourceSessionUuid: String? = null,
    /** True when filtered artifact differs from full export (fewer rows / trim). */
    val filterChangedPayload: Boolean = false,
    /** Populated when upload was not attempted ([BatchUploadState.SKIPPED]). */
    val uploadSkipReason: String? = null,
    /** SHA-256 of the prepared ZIP; used to resume after WorkManager retry without re-exporting. */
    val contentChecksumSha256: String? = null,
    /** [org.openlgx.roads.export] artifact kind (e.g. RAW_UPLOAD, FILTERED_UPLOAD). */
    val artifactKind: String? = null,
)
