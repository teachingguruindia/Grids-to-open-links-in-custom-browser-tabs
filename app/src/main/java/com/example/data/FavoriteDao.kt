package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getAllFavoritesFlow(): Flow<List<FavoriteSite>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(site: FavoriteSite)

    @Delete
    suspend fun deleteFavorite(site: FavoriteSite)

    @Query("DELETE FROM favorites WHERE url = :url")
    suspend fun deleteFavoriteByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE url = :url LIMIT 1)")
    suspend fun isFavorite(url: String): Boolean
}
