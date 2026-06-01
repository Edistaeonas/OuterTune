package com.dd3boh.outertune.viewmodels

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.pages.ArtistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = database.artistSongsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isLoading = MutableStateFlow(true)

    init {
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            isLoading.value = true
            val artistFromDb = libraryArtist.first()?.artist
            val idForYouTube = artistFromDb?.channelId?.takeIf { it.isNotEmpty() } ?: artistId

            YouTube.artist(idForYouTube)
                .onSuccess {
                    artistPage = it
                }.onFailure {
                    artistPage = null
                }
            isLoading.value = false
        }
    }

    fun searchArtists(query: String, callback: (List<ArtistItem>) -> Unit) {
        viewModelScope.launch {
            YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST)
                .onSuccess { searchPage ->
                    val artists = searchPage.items.filterIsInstance<ArtistItem>().toMutableList()

                    // Automatically fetch up to 2 more pages of results to give the user more choices (total ~60 items)
                    var currentContinuation = searchPage.continuation
                    var pagesFetched = 0
                    while (currentContinuation != null && pagesFetched < 2) {
                        YouTube.searchContinuation(currentContinuation).onSuccess { next ->
                            artists.addAll(next.items.filterIsInstance<ArtistItem>())
                            currentContinuation = next.continuation
                        }.onFailure {
                            currentContinuation = null
                        }
                        pagesFetched++
                    }

                    callback(artists)
                }
                .onFailure {
                    reportException(it)
                    callback(emptyList())
                }
        }
    }


    fun linkArtist(selectedYouTubeArtistId: String, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val localArtistToUpdate = database.artistById(artistId) ?: return@launch

            YouTube.artist(selectedYouTubeArtistId).onSuccess { artistPageFromLink ->
                val ytArtist = artistPageFromLink.artist
                val existingArtist = database.artistByName(ytArtist.title)

                if (existingArtist != null && existingArtist.id != localArtistToUpdate.id) {
                    // MERGE into existing artist (This logic was correct and is preserved)
                    Log.i("ArtistLink", "Found existing artist '${existingArtist.name}'. Merging.")
                    database.updateSongArtistMap(oldId = localArtistToUpdate.id, newId = existingArtist.id)
                    val finalMergedArtist = existingArtist.copy(
                        channelId = ytArtist.channelId,
                        thumbnailUrl = ytArtist.thumbnail
                    )
                    database.update(finalMergedArtist)
                    database.delete(localArtistToUpdate)
                } else {
                    // --- DEFINITIVE FIX for BUG 2: TRANSFORM local artist into a linked artist ---
                    // This is for a new link where no duplicate exists.
                    Log.i("ArtistLink", "Transforming local artist '${localArtistToUpdate.name}' to linked artist.")
                    // 1. Create a new entity with the YouTube ID as its primary key.
                    val newLinkedArtist = ArtistEntity(
                        id = ytArtist.id, // The new ID is the YouTube ID
                        name = ytArtist.title,
                        channelId = ytArtist.channelId,
                        thumbnailUrl = ytArtist.thumbnail,
                        isLocal = true // Keep the local flag
                    )
                    // 2. Insert the new linked artist.
                    database.insert(newLinkedArtist)
                    // 3. Re-map all songs from the old local artist to the new linked artist.
                    database.updateSongArtistMap(oldId = localArtistToUpdate.id, newId = newLinkedArtist.id)
                    // 4. Delete the old local artist, whose identity has been replaced.
                    database.delete(localArtistToUpdate)
                }

                // Signal completion to the UI, which will navigate back.
                withContext(Dispatchers.Main) { onComplete() }

            }.onFailure { reportException(it) }
        }
    }

    // Replace the existing unlinkArtist function
    fun unlinkArtist(onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val linkedArtistEntity = database.artistById(artistId) ?: return@launch

            // --- DEFINITIVE FIX for BUG 1: TRANSFORM linked artist back to a local artist ---
            Log.i("ArtistLink", "Transforming linked artist '${linkedArtistEntity.name}' back to local.")
            // 1. Create a new local artist entity with a new, stable local ID.
            val newLocalArtist = ArtistEntity(
                id = ArtistEntity.generateArtistId(), // Generate a new, stable local-only ID
                name = linkedArtistEntity.name,
                isLocal = true
                // channelId and thumbnailUrl are null by default
            )
            // 2. Insert the new local artist.
            database.insert(newLocalArtist)
            // 3. Re-map all songs from the old linked artist to the new local artist.
            database.updateSongArtistMap(oldId = linkedArtistEntity.id, newId = newLocalArtist.id)
            // 4. Delete the old linked artist, whose identity has been replaced.
            database.delete(linkedArtistEntity)

            // Signal completion to the UI, which will navigate back.
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}