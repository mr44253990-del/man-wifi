package com.example.data.database

import kotlinx.coroutines.flow.Flow

class RadarRepository(private val dao: RadarEventDao) {
    val allEvents: Flow<List<RadarEvent>> = dao.getAllEvents()

    suspend fun insert(event: RadarEvent) {
        dao.insertEvent(event)
    }

    suspend fun deleteById(id: Long) {
        dao.deleteEventById(id)
    }

    suspend fun clearAll() {
        dao.clearAllEvents()
    }
}
