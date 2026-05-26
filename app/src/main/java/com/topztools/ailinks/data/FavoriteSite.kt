package com.topztools.ailinks.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteSite(
    @PrimaryKey val url: String,
    val name: String,
    val description: String,
    val iconUrl: String,
    val colorHex: String,
    val isCustom: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
