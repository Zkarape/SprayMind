package com.cropguard.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "yard_profiles")
data class YardProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val widthMeters: Int,
    val heightMeters: Int,
    // Optional GPS coordinates — enables weather-aware notification scheduling.
    // Stored as nullable so existing yards without location are not affected.
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastVisitAt: Long = System.currentTimeMillis()
) {
    val totalSquareMeters: Int get() = widthMeters * heightMeters
    val hasLocation: Boolean get() = latitudeDeg != null && longitudeDeg != null
}
