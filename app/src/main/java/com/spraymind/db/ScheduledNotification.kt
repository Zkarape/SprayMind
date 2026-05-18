package com.spraymind.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scheduled_notifications",
    foreignKeys = [ForeignKey(
        entity        = YardProfile::class,
        parentColumns = ["id"],
        childColumns  = ["yardProfileId"],
        onDelete      = ForeignKey.CASCADE
    )],
    indices = [Index("yardProfileId")]
)
data class ScheduledNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val yardProfileId: Long,
    val cellKey: String,          // "row:col" for cell-specific, "yard" for yard-wide
    val title: String,
    val body: String,
    val scheduledForMs: Long,
    val urgency: String,          // low | medium | high
    val workRequestId: String = "" // WorkManager UUID — needed to cancel if cell heals
)
