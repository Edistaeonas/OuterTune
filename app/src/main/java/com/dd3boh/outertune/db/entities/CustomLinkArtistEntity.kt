package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

@Immutable
@Entity(tableName = "customlink_artist")
data class CustomLinkArtistEntity(
    @PrimaryKey val originalName: String,
    val customLink: String,
)
