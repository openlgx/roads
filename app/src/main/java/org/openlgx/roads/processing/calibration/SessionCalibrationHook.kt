package org.openlgx.roads.processing.calibration

import org.openlgx.roads.data.local.db.model.SessionState

/**
 * Phase F: optional [org.openlgx.roads.data.local.db.entity.CalibrationRunEntity] anchors when the
 * calibration workflow is enabled. See `docs/calibration-notes.md` in the repository.
 */
fun interface SessionCalibrationHook {

    suspend fun onRecordingSessionEnded(
        sessionId: Long,
        endState: SessionState,
        endedAtEpochMs: Long,
        calibrationWorkflowEnabled: Boolean,
    )
}
