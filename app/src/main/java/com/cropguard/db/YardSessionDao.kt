package com.cropguard.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface YardSessionDao {

    @Insert
    suspend fun insert(session: YardSession): Long

    // Returns the two most recent completed sessions — used for before/after comparison card.
    @Query("""
        SELECT * FROM yard_sessions
        WHERE yardProfileId = :yardId AND finishedAt IS NOT NULL
        ORDER BY startedAt DESC
        LIMIT 2
    """)
    suspend fun getLastTwo(yardId: Long): List<YardSession>

    // Returns the N most recent completed sessions — used by SessionAdvisor for cell history.
    @Query("""
        SELECT * FROM yard_sessions
        WHERE yardProfileId = :yardId AND finishedAt IS NOT NULL
        ORDER BY startedAt DESC
        LIMIT :n
    """)
    suspend fun getLastN(yardId: Long, n: Int): List<YardSession>

    @Query("""
        SELECT * FROM yard_sessions
        WHERE yardProfileId = :yardId
        ORDER BY startedAt DESC
    """)
    suspend fun getAllForYard(yardId: Long): List<YardSession>

    @Query("""
        UPDATE yard_sessions
        SET finishedAt = :finishedAt,
            peakSeverity = :peakSeverity,
            totalScans = :totalScans,
            avgPressureBar = :avgPressure,
            notes = :notes
        WHERE id = :id
    """)
    suspend fun finish(
        id: Long,
        finishedAt: Long,
        peakSeverity: String,
        totalScans: Int,
        avgPressure: Float,
        notes: String
    )
}
