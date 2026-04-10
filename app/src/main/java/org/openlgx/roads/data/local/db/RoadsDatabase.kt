package org.openlgx.roads.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import org.openlgx.roads.data.local.db.dao.AnomalyCandidateDao
import org.openlgx.roads.data.local.db.dao.CalibrationRunDao
import org.openlgx.roads.data.local.db.dao.DerivedWindowFeatureDao
import org.openlgx.roads.data.local.db.dao.DeviceProfileDao
import org.openlgx.roads.data.local.db.dao.LocationSampleDao
import org.openlgx.roads.data.local.db.dao.RecordingSessionDao
import org.openlgx.roads.data.local.db.dao.SegmentConsensusRecordDao
import org.openlgx.roads.data.local.db.dao.SensorSampleDao
import org.openlgx.roads.data.local.db.dao.UploadBatchDao
import org.openlgx.roads.data.local.db.entity.AnomalyCandidateEntity
import org.openlgx.roads.data.local.db.entity.CalibrationRunEntity
import org.openlgx.roads.data.local.db.entity.DerivedWindowFeatureEntity
import org.openlgx.roads.data.local.db.entity.DeviceProfileEntity
import org.openlgx.roads.data.local.db.entity.LocationSampleEntity
import org.openlgx.roads.data.local.db.entity.RecordingSessionEntity
import org.openlgx.roads.data.local.db.entity.SegmentConsensusRecordEntity
import org.openlgx.roads.data.local.db.entity.SensorSampleEntity
import org.openlgx.roads.data.local.db.entity.UploadBatchEntity

@Database(
    entities = [
        DeviceProfileEntity::class,
        RecordingSessionEntity::class,
        LocationSampleEntity::class,
        SensorSampleEntity::class,
        UploadBatchEntity::class,
        DerivedWindowFeatureEntity::class,
        AnomalyCandidateEntity::class,
        SegmentConsensusRecordEntity::class,
        CalibrationRunEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class RoadsDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun locationSampleDao(): LocationSampleDao
    abstract fun sensorSampleDao(): SensorSampleDao
    abstract fun uploadBatchDao(): UploadBatchDao
    abstract fun derivedWindowFeatureDao(): DerivedWindowFeatureDao
    abstract fun anomalyCandidateDao(): AnomalyCandidateDao
    abstract fun segmentConsensusRecordDao(): SegmentConsensusRecordDao
    abstract fun calibrationRunDao(): CalibrationRunDao
}
