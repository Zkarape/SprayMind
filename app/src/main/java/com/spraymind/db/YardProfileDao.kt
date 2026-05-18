package com.spraymind.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface YardProfileDao {

    @Query("SELECT * FROM yard_profiles ORDER BY lastVisitAt DESC")
    suspend fun getAll(): List<YardProfile>

    @Query("SELECT * FROM yard_profiles WHERE id = :id")
    suspend fun getById(id: Long): YardProfile?

    @Insert
    suspend fun insert(profile: YardProfile): Long

    @Update
    suspend fun update(profile: YardProfile)

    @Query("UPDATE yard_profiles SET lastVisitAt = :time WHERE id = :id")
    suspend fun updateLastVisit(id: Long, time: Long = System.currentTimeMillis())
}
