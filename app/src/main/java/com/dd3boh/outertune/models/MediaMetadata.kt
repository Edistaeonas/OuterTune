package com.dd3boh.outertune.models

import android.util.Log
import androidx.compose.runtime.Immutable
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.LocalArtworkPath
import com.zionhuang.innertube.models.SongItem
import java.io.Serializable
import java.time.LocalDateTime
import java.time.ZoneOffset

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val album: Album? = null,
    val genre: List<Genre>?,
    val year: Int? = null,
    private val date: LocalDateTime? = null, // ID3 tag property
    private val dateModified: LocalDateTime? = null, // file property
    val inLibrary: LocalDateTime? = null, // doubles as "date added"
    val setVideoId: String? = null,
    val isLocal: Boolean = false,
    val localPath: String? = null,
    val liked: Boolean = false,
    val composeUidWorkaround: Double = Math.random(), // compose will crash without this hax
    val parentArtist: String? = null, // Origin artist of the "Artist's Radio" functionality.
    var shuffleIndex: Int = -1,
    val albumArtist: String? = null,
    val commentTag: String? = null,
    val composer: String? = null,
) : Serializable {
    data class Artist(
        val id: String?,
        val name: String,
        val isLocal: Boolean = false,
    ) : Serializable

    data class Album(
        val id: String,
        val title: String,
        val isLocal: Boolean = false,
    ) : Serializable {
        // Add this property to get a version of the title without the § marker
        val displayTitle: String
            get() = title.removePrefix("§")
    }

    data class Genre(
        val id: String?,
        val title: String,
        val isLocal: Boolean = false,
    ) : Serializable

    fun toSongEntity() = SongEntity(
        id = id,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        trackNumber = trackNumber,
        discNumber = discNumber,
        albumId = album?.id,
        albumName = album?.title,
        albumArtist = albumArtist,
        year = year,
        date = date,
        dateModified = dateModified,
        liked = liked,
        isLocal = isLocal,
        inLibrary = if (isLocal) LocalDateTime.now() else null,
        localPath = localPath,
        commentTag = commentTag,
        composer = composer,
    )

    /**
     * Returns a full date string. If no full date is present, returns the year.
     * This is the song's tag's date/year, NOT dateModified.
     */
    fun getDateString(): String? {
        return date?.toLocalDate()?.toString()
            ?: if (year != null) {
                return year.toString()
            } else {
                return null
            }
    }

    /**
     * Returns a full date modified string
     */
    fun getDateModifiedString(): String? {
        return dateModified?.toLocalDate()?.toString()
    }

    /**
     * Get the value of the date released in Epoch Seconds
     */
    fun getDateLong(): Long? = date?.toEpochSecond(ZoneOffset.UTC)

    /**
     * Get the value of the date modified in Epoch Seconds
     */
    fun getDateModifiedLong(): Long? = dateModified?.toEpochSecond(ZoneOffset.UTC)

    fun getThumbnailModel(sizeX: Int = -1, sizeY: Int = -1): Any? {
        // --- DEFINITIVE FIX: Allow online covers for local songs ---
        // If we have an enriched online cover (URL starts with http), use it directly.
        if (thumbnailUrl?.startsWith("http") == true) {
            return thumbnailUrl
        }

        // Otherwise, use the standard logic
        return if (isLocal) {
            LocalArtworkPath(thumbnailUrl ?: localPath, sizeX, sizeY)
        } else {
            thumbnailUrl
        }
    }
}

fun Song.toMediaMetadata() = MediaMetadata(
    id = song.id,
    title = song.title,
    artists = artists.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name,
            isLocal = it.isLocal
        )
    },
    duration = song.duration,

    // If the song is local and the database thumbnailUrl is null,
    // use the file's own path. The Coil image loader knows how to handle this
    // and extract the embedded artwork, preventing an unnecessary network call.
    thumbnailUrl = if (song.isLocal && song.thumbnailUrl == null) {
        song.localPath
    } else {
        song.thumbnailUrl
    },

    trackNumber = song.trackNumber,
    discNumber = song.discNumber,
    albumArtist = song.albumArtist,
    album = album?.let {
        Log.d("MediaMetadata", "toMediaMetadata: Song '${song.title}' linked to Album Entity: '${it.title}' (ID: ${it.id})")
        MediaMetadata.Album(
            id = it.id,
            title = it.title,
            isLocal = it.isLocal
        )
    } ?: song.albumId?.let { albumId ->
        Log.d("MediaMetadata", "toMediaMetadata: Song '${song.title}' has no Relation, using albumName field: '${song.albumName}'")
        // Mark as fallback if the name starts with our special symbol (means it's a folder name as album name, because the file had no album tag)
        val isFallback = song.albumName?.startsWith("§") == true
        MediaMetadata.Album(
            id = if (isFallback) "fallback_folder_${song.albumName}" else albumId,
            title = song.albumName.orEmpty(),
        )
    } ?: if (!song.albumName.isNullOrBlank()) {
        val isFallback = song.albumName!!.startsWith("§")
        MediaMetadata.Album(
            id = if (isFallback) "fallback_folder_${song.albumName}" else "local_tag_album_${song.albumName}",
            title = song.albumName!!,
            isLocal = true
        )
    } else null,
    genre = if (genre.isNullOrEmpty()) {
        Log.d("Edgardebug", "genre is missing for song: ${song.title}")
        listOf(MediaMetadata.Genre(id = null, title = "unknown genre", isLocal = song.isLocal))
    } else {
        val mappedGenres = genre.map {
            MediaMetadata.Genre(id = it.id, title = it.title, isLocal = it.isLocal)
        }
        Log.d("Edgardebug", "Found genres for song '${song.title}': ${mappedGenres.joinToString { it.title }}")
        mappedGenres
    },
    year = song.year ?: album?.year,
    date = song.date,
    dateModified = song.dateModified,
    inLibrary = song.inLibrary,
    liked = song.liked,
    isLocal = song.isLocal,
    localPath = song.localPath,
    commentTag = song.commentTag,
    composer = song.composer,
)

fun SongItem.toMediaMetadata(parentArtist: String? = null) = MediaMetadata(
    id = id,
    title = title,
    artists = artists.map {
        MediaMetadata.Artist(
            id = it.id,
            name = it.name
        )
    },
    duration = duration ?: -1,
    thumbnailUrl = thumbnail.resize(544, 544),
    album = album?.let {
        MediaMetadata.Album(
            id = it.id,
            title = it.name
        )
    },
    genre = null,
    setVideoId = setVideoId,
    parentArtist = parentArtist
)
