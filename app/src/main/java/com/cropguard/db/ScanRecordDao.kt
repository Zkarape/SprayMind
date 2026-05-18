package com.cropguard.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScanRecordDao {

    @Insert
    suspend fun insert(record: ScanRecord): Long

    @Query("SELECT * FROM scan_records WHERE sessionId = :sessionId ORDER BY squareMeter ASC")
    suspend fun getForSession(sessionId: Long): List<ScanRecord>
}
