package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RadarEventDao {
    @Query("SELECT * FROM radar_events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<RadarEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: RadarEvent)

    @Query("DELETE FROM radar_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("DELETE FROM radar_events")
    suspend fun clearAllEvents()
}
