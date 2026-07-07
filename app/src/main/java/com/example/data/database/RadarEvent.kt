package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "radar_events")
data class RadarEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val baseRssi: Int,
    val dropMagnitude: Int,
    val status: String,
    val inference: String
)
