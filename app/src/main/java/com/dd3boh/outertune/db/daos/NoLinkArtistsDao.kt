package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dd3boh.outertune.db.entities.NoLinkArtistEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoLinkArtistsDao {
    @Query("SELECT * FROM nolink_artist ORDER BY name ASC")
    fun getAllNoLinkArtists(): Flow<List<NoLinkArtistEntity>>

    @Query("SELECT * FROM nolink_artist ORDER BY name ASC")
    fun getAllNoLinkArtistsSync(): List<NoLinkArtistEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: NoLinkArtistEntity)

    @Delete
    fun delete(artist: NoLinkArtistEntity)
}
