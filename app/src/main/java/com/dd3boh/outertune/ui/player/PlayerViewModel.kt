package com.dd3boh.outertune.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.MetadataEnricher
import com.dd3boh.outertune.utils.sanitizeMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val db: MusicDatabase
) : ViewModel() {

    private val _fullMediaMetadata = MutableStateFlow<MediaMetadata?>(null)
    val fullMediaMetadata = _fullMediaMetadata.asStateFlow()

    private var enrichmentJob: Job? = null
    private var lastEnrichedId: String? = null

    fun updateNowPlaying(mediaMetadata: MediaMetadata?) {
        if (mediaMetadata == null) {
            _fullMediaMetadata.value = null
            enrichmentJob?.cancel()
            return
        }
        Log.i("PlayerVM", "ENRICH METADATA : Before check is need better album, now we have Album='${mediaMetadata.album?.title}' ")

        val previousId = _fullMediaMetadata.value?.id
        // Immediately update with what we have from the service
        _fullMediaMetadata.value = mediaMetadata

        // If the song changed, cancel the previous enrichment attempt
        if (previousId != mediaMetadata.id) {
            enrichmentJob?.cancel()
        } else if (lastEnrichedId == mediaMetadata.id) {
            // ALREADY ENRICHED: Don't trigger again for the same song in this session
            return
        }


        // Determine if enrichment is needed
        // Use the § marker to decide if we need to search for a better album name
        val isFallbackAlbum = mediaMetadata.album?.title?.startsWith("§") == true
        val needsAlbum = mediaMetadata.album == null || isFallbackAlbum
        val needsYear = mediaMetadata.year == null
        val needsArt = mediaMetadata.thumbnailUrl == null || mediaMetadata.thumbnailUrl == mediaMetadata.localPath

        // Check lastEnrichedId to stop immediately if we already tried this song
        if ((needsAlbum || needsYear || needsArt) &&
            enrichmentJob?.isActive != true &&
            lastEnrichedId != mediaMetadata.id) {

            Log.i("PlayerVM", "ENRICH METADATA : Triggered for '${mediaMetadata.title}'. " +
                    "Needs: Album=$needsAlbum, Year=$needsYear, Art=$needsArt")
            Log.i("PlayerVM", "ENRICH METADATA : Current State: Album='${mediaMetadata.album?.title}', " +
                    "Year=${mediaMetadata.year}, LocalPath='${mediaMetadata.localPath}'")

            val currentId = mediaMetadata.id
            enrichmentJob = viewModelScope.launch(Dispatchers.IO) {

                val artistName = mediaMetadata.artists.firstOrNull()?.name ?: return@launch
                val songTitle = mediaMetadata.title

                // Check if the local file has a VALID, decodable image once per song
                var hasValidLocalArt = false
                if (mediaMetadata.isLocal && mediaMetadata.localPath != null) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(mediaMetadata.localPath)
                        val artBytes = retriever.embeddedPicture
                        if (artBytes != null) {
                            // Verify it can actually be decoded
                            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size, options)
                            hasValidLocalArt = options.outWidth > 0 && options.outHeight > 0
                        }
                        retriever.release()
                    } catch (e: Exception) { /* assume invalid */ }
                }

                val result = MetadataEnricher.findEnrichment(songTitle, artistName)

                // Mark as attempted so we don't loop
                Log.i("PlayerVM", "ENRICH METADATA lastEnrichedId = currentId $currentId --- Mark as attempted so we don't loop!!!")
                lastEnrichedId = currentId

                if (result != null && isActive && _fullMediaMetadata.value?.id == currentId) {
                    applyEnrichedMetadata(currentId, result, hasValidLocalArt)
                }
            }
        }
    }

    /**
     * Internal helper to merge the fetched YouTube data with the current player state.
     */
    fun applyEnrichedMetadata(
        currentId: String,
        result: MetadataEnricher.EnrichmentResult,
        hasValidLocalArt: Boolean
    ) {

        val currentMetadata = _fullMediaMetadata.value ?: return
        if (currentMetadata.id != currentId) return

        var updated = currentMetadata
        var wasUpdated = false
        val earliestYear = result.year
        val standardYear = result.standardYear
        val albumPage = result.albumPage ?: return
        val trackNumber = result.trackNumber



        // --- ORIGINAL YEAR LOGIC IMPACT LOG ---
        if (earliestYear != null && standardYear != null && earliestYear < standardYear) {
            Log.i("PlayerVM", "Original Year Finder impact for '${updated.title}': Found original year $earliestYear (standard result was $standardYear)")
        }

        // 1. Enrich Album Name - ALLOW REPLACING FALLBACKS (starting with §)
        val isFallbackAlbum = updated.album?.title?.startsWith("§") == true
        if (updated.album == null || isFallbackAlbum) {
            val newAlbumName = result.albumTitle ?: sanitizeMetadata(albumPage.album.title)
            if (updated.album?.title != newAlbumName) {
                Log.i("PlayerVM", "ENRICH METADATA : Enriching song '${updated.title}' with album: '$newAlbumName' (was '${updated.album?.title}')")
                updated = updated.copy(
                    album = MediaMetadata.Album(albumPage.album.browseId, newAlbumName)
                )
                wasUpdated = true
            }
        }
        
        // Enrich Year (Use the earliest year found in the search process)
        if (updated.year == null && earliestYear != null) {
            Log.i("PlayerVM", "ENRICH METADATA : Enriching song '${updated.title}' with year: $earliestYear - id ${updated.id}")
            updated = updated.copy(year = earliestYear)
            wasUpdated = true
        }

        // Enrich Track Number
        if (updated.trackNumber == null && result.trackNumber != null) {
            Log.i("PlayerVM", "ENRICH METADATA : Enriching song '${updated.title}' with track number: ${result.trackNumber}")
            updated = updated.copy(trackNumber = result.trackNumber)
            wasUpdated = true
        }

        // Enrich Artwork (Only if local art is missing or unreadable)
        if (!hasValidLocalArt && (updated.thumbnailUrl == null || updated.thumbnailUrl == updated.localPath)) {
            albumPage.album.thumbnail.let { onlineArt ->
                Log.i("PlayerVM", "ENRICH METADATA : Enriching song '${updated.title}' with thumbnail: $onlineArt")
                updated = updated.copy(thumbnailUrl = onlineArt)
                wasUpdated = true
            }
        }

        if (wasUpdated) {
            _fullMediaMetadata.value = updated

            // FIX: Use 'updated' (which has the new album name) instead of 'currentMetadata'
            val songToSave = updated.toSongEntity().copy(
                year = updated.year,
                trackNumber = trackNumber,
                albumArtist = result.albumArtist,
            )

            db.query {
                // 1. Ensure all artists for this album exist in the DB
                albumPage.album.artists?.forEach { artist ->
                    val artistId = artist.id ?: com.dd3boh.outertune.db.entities.ArtistEntity.generateArtistId()
                    insert(com.dd3boh.outertune.db.entities.ArtistEntity(
                        id = artistId,
                        name = artist.name,
                        isLocal = false
                    ))
                }

                // 2. Map and Insert the Album record
                val albumEntity = com.dd3boh.outertune.db.entities.AlbumEntity(
                    id = albumPage.album.browseId,
                    title = result.albumTitle ?: albumPage.album.title,
                    year = earliestYear,
                    thumbnailUrl = albumPage.album.thumbnail,
                    songCount = albumPage.songs.size,
                    duration = albumPage.songs.sumOf { it.duration ?: 0 },
                    isLocal = false
                )
                insert(albumEntity)

                // 3. Link Album to its Artists (order field)
                albumPage.album.artists?.forEachIndexed { idx, artist ->
                    val artistId = artist.id ?: return@forEachIndexed
                    insert(com.dd3boh.outertune.db.entities.AlbumArtistMap(
                        albumId = albumEntity.id,
                        artistId = artistId,
                        order = idx
                    ))
                }

                // 4. IMPORTANT: Force insert/update the song record BEFORE linking it to the album
                // This ensures the song exists to satisfy the Foreign Key constraint
                val songEntity = songToSave.copy(albumId = albumEntity.id)
                if (insert(songEntity) == -1L) {
                    update(songEntity)
                }

                // 5. Link the Song to the Album (index field)
                unlinkSongAlbums(songEntity.id)
                insert(com.dd3boh.outertune.db.entities.SongAlbumMap(
                    songId = songEntity.id,
                    albumId = albumEntity.id,
                    index = result.trackNumber ?: 0
                ))
            }


            Log.i("PlayerVM", "Successfully enriched and saved metadata for '${updated.title}'.")
        }
    }
}
