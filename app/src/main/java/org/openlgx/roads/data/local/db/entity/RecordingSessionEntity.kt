package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.openlgx.roads.data.local.db.model.RecordingSource
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.data.local.db.model.SessionUploadState

@Entity(
    tableName = "recording_sessions",
    indices = [Index("uuid"), Index("startedAtEpochMs")],
)
data class RecordingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    /** Passive pipeline: when ARMING began (epoch ms), if applicable. */
    val armedAtEpochMs: Long? = null,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val state: SessionState,
    val recordingSource: RecordingSource,
    /** Bitmask; quality flag semantics defined in docs, not enforced in Phase 1. */
    val qualityFlags: Int = 0,
    val sessionUploadState: SessionUploadState,
    val autoDetectionSnapshotJson: String? = null,
    /** Runtime collector lifecycle / activity snapshot at session start (Phase 2A). */
    val collectorStateSnapshotJson: String? = null,
    val roadEligibilitySummaryJson: String? = null,
    /** Snapshot of capture policy at session start (passive gate, etc.). */
    val capturePassiveCollectionEnabled: Boolean? = null,
    /** Optional human-readable note or JSON for extended capture policy. */
    val capturePolicySnapshotJson: String? = null,
    /** Upload policy snapshot (Wi‑Fi / cellular / charging / battery pause). */
    val uploadPolicyWifiOnly: Boolean? = null,
    val uploadPolicyAllowCellular: Boolean? = null,
    val uploadPolicyOnlyWhileCharging: Boolean? = null,
    val uploadPolicyPauseOnLowBattery: Boolean? = null,
    val uploadPolicyLowBatteryThresholdPercent: Int? = null,
    val roughnessProxyScore: Double? = null,
    val roughnessMethodVersion: String? = null,
    val roadResponseScore: Double? = null,
    val roadResponseMethodVersion: String? = null,
)
