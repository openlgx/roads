package org.openlgx.roads.upload

/** WorkManager [androidx.work.Data] keys for [SessionUploadWorker] progress (observed in Settings). */
object SessionUploadProgressKeys {
    const val PROGRESS_SESSION_ID = "progressSessionId"
    const val PROGRESS_PHASE = "progressPhase"
    const val PROGRESS_BYTES_UPLOADED = "progressBytesUploaded"
    const val PROGRESS_BYTES_TOTAL = "progressBytesTotal"
    /** During ZIP export: rows written (locations + sensors) vs estimated total rows. */
    const val PROGRESS_PREPARE_ROWS_DONE = "progressPrepareRowsDone"
    const val PROGRESS_PREPARE_ROWS_TOTAL = "progressPrepareRowsTotal"

    const val PHASE_PREPARE = "prepare"
    const val PHASE_UPLOAD = "upload"
}
