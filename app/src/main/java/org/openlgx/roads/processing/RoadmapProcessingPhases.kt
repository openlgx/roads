package org.openlgx.roads.processing

/**
 * Maps the README processing pipeline (Phases A–F) to code.
 *
 * Phase F is wired when [org.openlgx.roads.data.local.settings.AppSettings.calibrationWorkflowEnabled]
 * is true: [org.openlgx.roads.processing.calibration.SessionCalibrationHook] records session anchors.
 */
object RoadmapProcessingPhases {
    const val PREPROCESS = "A"
    const val WINDOWING = "C"
    const val FEATURES = "D"
    const val SCORING = "E"
    const val CALIBRATION = "F"
}
