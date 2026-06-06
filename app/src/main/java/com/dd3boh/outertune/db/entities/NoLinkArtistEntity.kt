package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "nolink_artist")
data class NoLinkArtistEntity(
    @PrimaryKey val name: String,
)
