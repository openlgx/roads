package org.openlgx.roads.processing.calibration

import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.entity.CalibrationRunEntity
import org.openlgx.roads.data.local.db.model.SessionState
import org.openlgx.roads.export.ExportConstants
import org.openlgx.roads.processing.RoadmapProcessingPhases

@Singleton
class SessionCalibrationHookImpl
@Inject
constructor(
    private val database: RoadsDatabase,
) : SessionCalibrationHook {

    override suspend fun onRecordingSessionEnded(
        sessionId: Long,
        endState: SessionState,
        endedAtEpochMs: Long,
        calibrationWorkflowEnabled: Boolean,
    ) {
        if (!calibrationWorkflowEnabled) return
        if (endState != SessionState.COMPLETED) return

        val parametersJson =
            JSONObject()
                .put("sessionId", sessionId)
                .put("endedAtEpochMs", endedAtEpochMs)
                .put("hookVersion", HOOK_VERSION)
                .put("roadmapPhase", RoadmapProcessingPhases.CALIBRATION)
                .put("calibrationLiteraturePointer", ExportConstants.CALIBRATION_LITERATURE_POINTER)
                .toString()

        database.calibrationRunDao().insert(
            CalibrationRunEntity(
                label = "session_completed_anchor",
                createdAtEpochMs = endedAtEpochMs,
                notes =
                    "Automatic anchor when Phase F calibration workflow is enabled. " +
                        "Not MEMS or profiler calibration; see calibration-notes.md.",
                parametersJson = parametersJson,
                status = "ANCHOR_RECORDED",
            ),
        )
    }

    private companion object {
        const val HOOK_VERSION: Int = 1
    }
}
