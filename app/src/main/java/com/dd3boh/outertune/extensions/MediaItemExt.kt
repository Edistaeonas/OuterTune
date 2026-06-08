package com.dd3boh.outertune.extensions

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.convertToStartCase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import com.zionhuang.innertube.models.SongItem

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

private val TAG = "MediaItemExt"

fun Song.toMediaItem() = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.id)
    .setCustomCacheKey(song.id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(convertToStartCase(song.title))
            .setSubtitle(artists.joinToString { it.name }) // Artist name kept as given
            .setArtist(artists.joinToString { it.name })   // Artist name kept as given
            .setArtworkUri(song.thumbnailUrl?.toUri())
            .setAlbumTitle(song.albumName?.let { convertToStartCase(it) })
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

fun SongItem.toMediaItem() = MediaItem.Builder()
    .setMediaId(id)
    .setUri(id)
    .setCustomCacheKey(id)
    .setTag(toMediaMetadata())
    .setMediaMetadata(
        androidx.media3.common.MediaMetadata.Builder()
            .setTitle(convertToStartCase(title))
            .setSubtitle(artists.joinToString { it.name }) // Artist name kept as given
            .setArtist(artists.joinToString { it.name })   // Artist name kept as given
            .setArtworkUri(thumbnail.toUri())
            .setAlbumTitle(album?.name?.let { convertToStartCase(it) })
            .setMediaType(MEDIA_TYPE_MUSIC)
            .build()
    )
    .build()

/**
 * Converts an application-specific MediaMetadata object into a Media3 MediaItem,
 * ready for use by the player. This includes special handling for Android Auto
 * metadata and local artwork.
 *
 * @param showCommentAndComposer If true, replaces Title/Artist with Composer/Comment for local files.
 */
fun MediaMetadata.toMediaItem(
    context: Context,
    includeParentArtist: Boolean = false,
    showCommentAndComposer: Boolean = false
): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(this.id)
        .setUri(this.id)
        .setCustomCacheKey(this.id)
        .setTag(this)

    // Apply Start Case transformation
    val startCaseTitle = convertToStartCase(this.title)
    val rawArtist = this.artists.joinToString { it.name }
    val startCaseAlbum = this.album?.let { convertToStartCase(it.displayTitle) }
    val tracknbr = this.trackNumber?.takeIf { it > 0 }?.toString()
    val titleWithTrackNbr = if (tracknbr != null) "[$tracknbr] - $startCaseTitle" else startCaseTitle

    // 1. Determine the Final Title (Show Composer if requested)
    val finalTitle = if (showCommentAndComposer && this.isLocal && !this.composer.isNullOrBlank()) {
        "© ${this.composer}"
    } else if (this.isLocal) {
        "¤ $titleWithTrackNbr"
    } else {
        titleWithTrackNbr
    }

    // 2. Determine the Final Artist/Subtitle string (Show Comment if requested)
    val concatenatedArtistString = if (showCommentAndComposer && this.isLocal && !this.commentTag.isNullOrBlank()) {
        // Flatten and clean multiline comments for car marquee
        val singleLineComment = this.commentTag!!.replace("\n", " ").replace("\r", " ").trim().replace(Regex("\\s+"), " ")
        "ⓘ $singleLineComment"
    } else {
        buildString {
            val isSameArtist = this@toMediaItem.parentArtist != null && this@toMediaItem.artists.any {
                it.name.equals(this@toMediaItem.parentArtist, ignoreCase = true)
            }

            if (includeParentArtist && this@toMediaItem.parentArtist != null && !isSameArtist) {
                append(context.getString(R.string.from_radio, this@toMediaItem.parentArtist!!))
            } else {
                // Standard format: Artist • Year • Album
                append(rawArtist)
                if (this@toMediaItem.year != null) { append(" • ${this@toMediaItem.year}") }
                if (startCaseAlbum != null) { append(" • $startCaseAlbum") }
            }
        }
    }

    // Populate Extras for MusicService to observe
    val extras = Bundle()
    extras.putBoolean("isLocal", this.isLocal || this.localPath != null)
    this.year?.let { extras.putString("year", it.toString()) }
    this.parentArtist?.let { extras.putString("parentArtist", it) }
    extras.putBoolean("isFallbackAlbum", this.album?.title?.startsWith("§") == true)

    val mediaMetadataBuilder = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(finalTitle)
        .setTrackNumber(this.trackNumber)
        .setArtist(concatenatedArtistString)
        .setSubtitle(concatenatedArtistString)
        .setAlbumArtist(concatenatedArtistString)
        .setAlbumTitle(startCaseAlbum)
        .setExtras(extras)
        .setReleaseYear(this.year)

    // --- Artwork Extraction for Local Files ---
    var artworkData: ByteArray? = null
    if (this.isLocal && this.localPath != null) {
        try {
            val file = java.io.File(this.localPath)
            if (file.exists()) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this.localPath)
                artworkData = retriever.embeddedPicture
                retriever.release()
            } else {
                Log.w("toMediaItem", "Skipping artwork extraction: File not found at ${this.localPath}")
            }
        } catch (e: Exception) {
            Log.e("toMediaItem", "Failed to extract embedded picture from ${this.localPath}", e)
        }
    }

    if (artworkData != null) {
        mediaMetadataBuilder.setArtworkData(artworkData, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    } else if (this.thumbnailUrl != null) {
        mediaMetadataBuilder.setArtworkUri(Uri.parse(this.thumbnailUrl))
    }

    val finalMediaMetadata = mediaMetadataBuilder.build()
    Log.i(TAG, "PUSHING TO CAR: Title='${finalMediaMetadata.title}', Artist='${finalMediaMetadata.artist}', Album='${finalMediaMetadata.albumTitle}'")

    builder.setMediaMetadata(finalMediaMetadata)
    return builder.build()
}