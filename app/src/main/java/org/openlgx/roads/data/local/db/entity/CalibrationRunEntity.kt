package org.openlgx.roads.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calibration_runs")
data class CalibrationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val createdAtEpochMs: Long,
    val notes: String? = null,
    val parametersJson: String? = null,
    val status: String? = null,
)
