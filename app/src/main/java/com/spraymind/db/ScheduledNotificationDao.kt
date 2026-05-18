package com.spraymind.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ScheduledNotificationDao {

    @Insert
    suspend fun insert(n: ScheduledNotification): Long

    @Query("SELECT * FROM scheduled_notifications WHERE yardProfileId = :yardId ORDER BY scheduledForMs ASC")
    suspend fun getForYard(yardId: Long): List<ScheduledNotification>

    @Query("DELETE FROM scheduled_notifications WHERE yardProfileId = :yardId AND cellKey IN (:cellKeys)")
    suspend fun dismissByCellKeys(yardId: Long, cellKeys: List<String>)

    @Query("UPDATE scheduled_notifications SET workRequestId = :requestId WHERE id = :id")
    suspend fun updateWorkRequestId(id: Long, requestId: String)
}
