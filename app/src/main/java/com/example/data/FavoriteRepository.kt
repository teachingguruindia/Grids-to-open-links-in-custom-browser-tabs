package com.example.data

import kotlinx.coroutines.flow.Flow

class FavoriteRepository(private val favoriteDao: FavoriteDao) {
    val allFavorites: Flow<List<FavoriteSite>> = favoriteDao.getAllFavoritesFlow()

    suspend fun addFavorite(site: FavoriteSite) {
        favoriteDao.insertFavorite(site)
    }

    suspend fun removeFavoriteByUrl(url: String) {
        favoriteDao.deleteFavoriteByUrl(url)
    }

    suspend fun isFavorite(url: String): Boolean {
        return favoriteDao.isFavorite(url)
    }
}
