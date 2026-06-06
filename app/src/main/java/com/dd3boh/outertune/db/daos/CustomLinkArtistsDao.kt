package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dd3boh.outertune.db.entities.CustomLinkArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomLinkArtistsDao {
    @Query("SELECT * FROM customlink_artist ORDER BY originalName ASC")
    fun getAllCustomLinkArtists(): Flow<List<CustomLinkArtistEntity>>

    @Query("SELECT * FROM customlink_artist ORDER BY originalName ASC")
    fun getAllCustomLinkArtistsSync(): List<CustomLinkArtistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(artist: CustomLinkArtistEntity)

    @Delete
    fun delete(artist: CustomLinkArtistEntity)
}
