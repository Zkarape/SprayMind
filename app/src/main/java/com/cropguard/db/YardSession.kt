package com.cropguard.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "yard_sessions",
    foreignKeys = [ForeignKey(
        entity = YardProfile::class,
        parentColumns = ["id"],
        childColumns = ["yardProfileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("yardProfileId")]
)
data class YardSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yardProfileId: Long,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    // Notes entered by the farmer after the session (e.g. "Applied neem oil", "After heavy rain")
    val notes: String = "",
    val peakSeverity: String = "NONE",   // Severity enum name
    val totalScans: Int = 0,
    val avgPressureBar: Float = 0f
)
