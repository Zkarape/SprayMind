package com.spraymind.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_records",
    foreignKeys = [ForeignKey(
        entity = YardSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val squareMeter: Int,
    val severity: String,        // Severity enum name
    val pests: String,
    val action: String,
    val pressureBar: Float,
    // How many identical frames were skipped by the dHash deduplicator before this scan landed.
    // Useful for knowing how long a section "held steady" before a meaningful change was detected.
    val skippedFrames: Int = 0,
    val analyzedAt: Long = System.currentTimeMillis()
)
