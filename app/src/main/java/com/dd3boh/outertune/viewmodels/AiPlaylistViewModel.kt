package com.dd3boh.outertune.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AiApiKeyKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.AiSong
import com.dd3boh.outertune.utils.GeminiClient
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

@HiltViewModel
class AiPlaylistViewModel @Inject constructor(
    application: Application,
    private val database: MusicDatabase,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<AiPlaylistUiState>(AiPlaylistUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _event = MutableStateFlow<AiPlaylistEvent?>(null)
    val event = _event.asStateFlow()

    fun generatePlaylist(prompt: String, songCount: Int = 20) {
        viewModelScope.launch {
            _uiState.value = AiPlaylistUiState.Generating
            
            val apiKey = getApplication<Application>().dataStore.get(AiApiKeyKey, "")
            val result = GeminiClient.generatePlaylist(apiKey, prompt, songCount)

            result.onSuccess { aiSongs ->
                _uiState.value = AiPlaylistUiState.Suggested(aiSongs)
            }.onFailure { error ->
                _uiState.value = AiPlaylistUiState.Error(error.message ?: "Unknown error")
            }
        }
    }

    fun resolveTracks(aiSongs: List<AiSong>) {
        viewModelScope.launch {
            _uiState.value = AiPlaylistUiState.Resolving(0, aiSongs.size)
            
            val resolvedTracks = mutableListOf<MediaMetadata>()
            
            aiSongs.forEachIndexed { index, aiSong ->
                _uiState.value = AiPlaylistUiState.Resolving(index + 1, aiSongs.size)
                
                // Search for the song on YouTube Music
                val searchResult = withContext(Dispatchers.IO) {
                    YouTube.search("${aiSong.artist} - ${aiSong.title}", YouTube.SearchFilter.FILTER_SONG)
                        .getOrNull()?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                }

                searchResult?.let { songItem ->
                    val track = songItem.toMediaMetadata()
                    resolvedTracks.add(track)
                    
                    // PRE-EMPTIVE FIX: Save track to DB as soon as it's resolved.
                    // This prevents FOREIGN KEY crashes in AddToPlaylistDialog later.
                    try {
                        withContext(Dispatchers.IO) {
                            database.insert(track)
                        }
                    } catch (e: Exception) {
                        Log.e("AiPlaylistViewModel", "Failed to pre-insert resolved track", e)
                    }
                }
            }

            if (resolvedTracks.isEmpty()) {
                _uiState.value = AiPlaylistUiState.Error(getApplication<Application>().getString(R.string.ai_playlist_error_no_tracks))
            } else {
                _uiState.value = AiPlaylistUiState.Resolved(resolvedTracks)
            }
        }
    }

    fun saveAsPlaylist(title: String, tracks: List<MediaMetadata>) {
        viewModelScope.launch(Dispatchers.IO) {
            val cookie = getApplication<Application>().dataStore.get(InnerTubeCookieKey, "")
            if (cookie.isEmpty()) {
                _event.value = AiPlaylistEvent.PromptLoginOrLocalSave(title, tracks)
                return@launch
            }

            try {
                _uiState.value = AiPlaylistUiState.Saving
                val playlistId = YouTube.createPlaylist(title)
                if (playlistId != null) {
                    YouTube.addSongsToPlaylist(playlistId, tracks.map { it.id })
                    _event.value = AiPlaylistEvent.PlaylistSaved
                } else {
                    _uiState.value = AiPlaylistUiState.Error("Failed to create playlist on YouTube Music")
                }
            } catch (e: Exception) {
                Log.e("AiPlaylistViewModel", "Failed to save playlist", e)
                _uiState.value = AiPlaylistUiState.Error("Failed to save playlist: ${e.message}")
            } finally {
                if (_uiState.value is AiPlaylistUiState.Saving) {
                    _uiState.value = AiPlaylistUiState.Resolved(tracks)
                }
            }
        }
    }

    fun saveAsLocalPlaylist(title: String, tracks: List<MediaMetadata>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = AiPlaylistUiState.Saving
                val playlistId = PlaylistEntity.generatePlaylistId()
                val playlist = PlaylistEntity(
                    id = playlistId,
                    name = title,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                    isLocal = true
                )

                database.query {
                    insert(playlist)
                    tracks.forEachIndexed { index, track ->
                        insert(track) // Ensure track exists in 'song' table first
                        insert(PlaylistSongMap(
                            playlistId = playlistId,
                            songId = track.id,
                            position = index
                        ))
                    }
                }
                _event.value = AiPlaylistEvent.PlaylistSaved
            } catch (e: Exception) {
                Log.e("AiPlaylistViewModel", "Failed to save local playlist", e)
                _uiState.value = AiPlaylistUiState.Error("Failed to save locally: ${e.message}")
            } finally {
                if (_uiState.value is AiPlaylistUiState.Saving) {
                    _uiState.value = AiPlaylistUiState.Resolved(tracks)
                }
            }
        }
    }

    fun resetEvent() {
        _event.value = null
    }

    fun reset() {
        _uiState.value = AiPlaylistUiState.Idle
    }

    sealed class AiPlaylistUiState {
        data object Idle : AiPlaylistUiState()
        data object Generating : AiPlaylistUiState()
        data class Suggested(val songs: List<AiSong>) : AiPlaylistUiState()
        data class Resolving(val current: Int, val total: Int) : AiPlaylistUiState()
        data class Resolved(val tracks: List<MediaMetadata>) : AiPlaylistUiState()
        data object Saving : AiPlaylistUiState()
        data class Error(val message: String) : AiPlaylistUiState()
    }

    sealed class AiPlaylistEvent {
        data object PlaylistSaved : AiPlaylistEvent()
        data class PromptLoginOrLocalSave(val title: String, val tracks: List<MediaMetadata>) : AiPlaylistEvent()
    }
}
