package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "blacklisted_artist")
data class BlacklistedArtistEntity(
    @PrimaryKey val name: String,
)
