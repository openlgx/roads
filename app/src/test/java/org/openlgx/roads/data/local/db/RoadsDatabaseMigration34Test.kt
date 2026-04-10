package org.openlgx.roads.data.local.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Validates SQL in [RoadsDatabaseMigrations.MIGRATION_3_4] against a v3-shaped database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoadsDatabaseMigration34Test {

    @Test
    fun migration_3_4_adds_columns_and_indexes() {
        val config =
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
                .builder(ApplicationProvider.getApplicationContext())
                .name("migration_test.db")
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `recording_sessions` (
                                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                  `uuid` TEXT NOT NULL,
                                  `armedAtEpochMs` INTEGER,
                                  `startedAtEpochMs` INTEGER NOT NULL,
                                  `endedAtEpochMs` INTEGER,
                                  `state` TEXT NOT NULL,
                                  `recordingSource` TEXT NOT NULL,
                                  `qualityFlags` INTEGER NOT NULL,
                                  `sessionUploadState` TEXT NOT NULL,
                                  `autoDetectionSnapshotJson` TEXT,
                                  `collectorStateSnapshotJson` TEXT,
                                  `sensorCaptureSnapshotJson` TEXT,
                                  `roadEligibilitySummaryJson` TEXT,
                                  `capturePassiveCollectionEnabled` INTEGER,
                                  `capturePolicySnapshotJson` TEXT,
                                  `uploadPolicyWifiOnly` INTEGER,
                                  `uploadPolicyAllowCellular` INTEGER,
                                  `uploadPolicyOnlyWhileCharging` INTEGER,
                                  `uploadPolicyPauseOnLowBattery` INTEGER,
                                  `uploadPolicyLowBatteryThresholdPercent` INTEGER,
                                  `roughnessProxyScore` REAL,
                                  `roughnessMethodVersion` TEXT,
                                  `roadResponseScore` REAL,
                                  `roadResponseMethodVersion` TEXT
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_recording_sessions_uuid` ON `recording_sessions` (`uuid`)",
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_recording_sessions_startedAtEpochMs` ON `recording_sessions` (`startedAtEpochMs`)",
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `derived_window_features` (
                                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                  `sessionId` INTEGER NOT NULL,
                                  `windowStartElapsedNanos` INTEGER NOT NULL,
                                  `windowEndElapsedNanos` INTEGER NOT NULL,
                                  `windowLengthMetersApprox` REAL,
                                  `methodVersion` TEXT,
                                  `isExperimental` INTEGER NOT NULL,
                                  `roughnessProxyScore` REAL,
                                  `roadResponseScore` REAL,
                                  `qualityFlags` INTEGER NOT NULL,
                                  `roadEligibilityDisposition` TEXT NOT NULL,
                                  `eligibilityConfidence` REAL,
                                  `featureBundleJson` TEXT,
                                  FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_derived_window_features_sessionId` ON `derived_window_features` (`sessionId`)",
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `anomaly_candidates` (
                                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                  `sessionId` INTEGER NOT NULL,
                                  `timeElapsedRealtimeNanos` INTEGER,
                                  `anomalyType` TEXT NOT NULL,
                                  `score` REAL,
                                  `confidence` REAL,
                                  `methodVersion` TEXT,
                                  `qualityFlags` INTEGER NOT NULL,
                                  `detailsJson` TEXT,
                                  FOREIGN KEY(`sessionId`) REFERENCES `recording_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE INDEX IF NOT EXISTS `index_anomaly_candidates_sessionId` ON `anomaly_candidates` (`sessionId`)",
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS `segment_consensus_records` (
                                  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                  `segmentKey` TEXT NOT NULL,
                                  `observedCount` INTEGER NOT NULL,
                                  `distinctDeviceCount` INTEGER NOT NULL,
                                  `consensusRoughnessProxy` REAL,
                                  `stabilityScore` REAL,
                                  `methodVersion` TEXT,
                                  `lastUpdatedEpochMs` INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                "CREATE UNIQUE INDEX IF NOT EXISTS `index_segment_consensus_records_segmentKey` ON `segment_consensus_records` (`segmentKey`)",
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            // not used
                        }
                    },
                )
                .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        helper.writableDatabase.use { db ->
            RoadsDatabaseMigrations.MIGRATION_3_4.migrate(db)
        }
        helper.writableDatabase.use { db ->
            db.query("PRAGMA table_info(recording_sessions)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) {
                    names.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
                assertThat(names).contains("processingState")
                assertThat(names).contains("processingSummaryJson")
            }
            db.query("PRAGMA table_info(derived_window_features)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) {
                    names.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
                assertThat(names).contains("windowIndex")
                assertThat(names).contains("predPrimaryLabel")
            }
            db.query("PRAGMA table_info(anomaly_candidates)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) {
                    names.add(c.getString(c.getColumnIndexOrThrow("name")))
                }
                assertThat(names).contains("wallClockUtcEpochMs")
                assertThat(names).contains("derivedWindowId")
            }
        }
        helper.close()
    }
}
