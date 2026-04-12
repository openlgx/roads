package org.openlgx.roads.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object RoadsDatabaseMigrations {

    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN processingState TEXT NOT NULL DEFAULT 'NOT_STARTED'",
                )
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN processingStartedAtEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN processingCompletedAtEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN processingLastError TEXT",
                )
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN processingSummaryJson TEXT",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recording_sessions_state_startedAtEpochMs` " +
                        "ON `recording_sessions` (`state`, `startedAtEpochMs`)",
                )

                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN windowStartWallClockUtcEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN windowEndWallClockUtcEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN midLatitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN midLongitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN meanSpeedMps REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN headingMeanDeg REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN predPrimaryLabel TEXT",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN scoreCornering REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN scoreVerticalShock REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN scoreStableCruise REAL",
                )
                db.execSQL(
                    "ALTER TABLE derived_window_features ADD COLUMN windowIndex INTEGER",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_derived_window_features_sessionId_windowIndex` " +
                        "ON `derived_window_features` (`sessionId`, `windowIndex`)",
                )

                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN wallClockUtcEpochMs INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN latitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN longitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN speedMps REAL",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN headingDeg REAL",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN severityBucket TEXT",
                )
                db.execSQL(
                    "ALTER TABLE anomaly_candidates ADD COLUMN derivedWindowId INTEGER",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_anomaly_candidates_sessionId_wallClockUtcEpochMs` " +
                        "ON `anomaly_candidates` (`sessionId`, `wallClockUtcEpochMs`)",
                )

                db.execSQL(
                    "ALTER TABLE segment_consensus_records ADD COLUMN centroidLatitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE segment_consensus_records ADD COLUMN centroidLongitude REAL",
                )
                db.execSQL(
                    "ALTER TABLE segment_consensus_records ADD COLUMN binSizeMetersApprox INTEGER",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_segment_consensus_records_centroidLatitude_centroidLongitude` " +
                        "ON `segment_consensus_records` (`centroidLatitude`, `centroidLongitude`)",
                )
            }
        }

    val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recording_sessions ADD COLUMN hostedPipelineState TEXT NOT NULL DEFAULT 'NOT_STARTED'",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN localRawArtifactUri TEXT",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN localFilteredArtifactUri TEXT",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN remoteUploadJobId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN remoteStorageKey TEXT",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN roadFilterSummaryJson TEXT",
                )
                db.execSQL(
                    "ALTER TABLE upload_batches ADD COLUMN sourceSessionUuid TEXT",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recording_sessions_hostedPipelineState` " +
                        "ON `recording_sessions` (`hostedPipelineState`)",
                )
            }
        }

    val MIGRATION_5_6: Migration =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_batches ADD COLUMN filterChangedPayload INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE upload_batches ADD COLUMN uploadSkipReason TEXT")
            }
        }

    val MIGRATION_6_7: Migration =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_batches ADD COLUMN contentChecksumSha256 TEXT")
                db.execSQL("ALTER TABLE upload_batches ADD COLUMN artifactKind TEXT")
            }
        }
}
