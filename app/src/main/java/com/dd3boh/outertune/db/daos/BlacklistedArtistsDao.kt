package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dd3boh.outertune.db.entities.BlacklistedArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistedArtistsDao {
    @Query("SELECT * FROM blacklisted_artist ORDER BY name ASC")
    fun getAllBlacklistedArtists(): Flow<List<BlacklistedArtistEntity>>

    @Query("SELECT * FROM blacklisted_artist ORDER BY name ASC")
    fun getAllBlacklistedArtistsSync(): List<BlacklistedArtistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: BlacklistedArtistEntity)

    @Delete
    fun delete(artist: BlacklistedArtistEntity)
}
