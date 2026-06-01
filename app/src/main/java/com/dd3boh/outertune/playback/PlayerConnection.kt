/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.compose.ui.geometry.isEmpty
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.getCurrentQueueIndex
import com.dd3boh.outertune.extensions.getQueueWindows
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.utils.SemanticLyrics

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    val binder: MediaControllerViewModel,
    val database: MusicDatabase,
) : Player.Listener {
    val TAG = PlayerConnection::class.simpleName.toString()

    val service = binder.getService()!!
    val player = service.player
    val scope = binder.viewModelScope

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    val isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
        playWhenReady && playbackState != STATE_ENDED
    }.stateIn(scope, SharingStarted.Lazily, player.playWhenReady && player.playbackState != STATE_ENDED)

    val isBuffering = playbackState.map { it == Player.STATE_BUFFERING }
        .stateIn(scope, SharingStarted.Lazily, player.playbackState == Player.STATE_BUFFERING)

    val waitingForNetworkConnection: StateFlow<Boolean> = service.waitingForNetworkConnection.asStateFlow()
    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong = mediaMetadata.flatMapLatest {
        database.song(it?.id)
    }
    val currentLyrics: Flow<SemanticLyrics> = mediaMetadata.flatMapLatest { mediaMetadata ->
        if (mediaMetadata != null) {
            return@flatMapLatest flowOf(service.lyricsHelper.getLyrics(mediaMetadata) ?: uninitializedLyric)
        } else {
            return@flatMapLatest flowOf()
        }
    }

    private val currentMediaItemIndex = MutableStateFlow(-1)

    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())

    var queuePlaylistId = MutableStateFlow<String?>(null)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)

    init {
        Log.d(TAG, "PlayerConnection: START of init.")
        player.addListener(this)

        playbackState.value = player.playbackState
        playWhenReady.value = player.playWhenReady
        queuePlaylistId.value = service.queuePlaylistId
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        currentMediaItemIndex.value = player.currentMediaItemIndex
        shuffleModeEnabled.value = player.shuffleModeEnabled
        repeatMode.value = player.repeatMode

//      THIS WAS CAUSING A CRASH AT STARTUP (indexOutOfBoundsException) if a queue has been loaded previously.
        // The crash (an `IndexOutOfBoundsException` deep in the Compose UI layer) was caused
        // by a race condition:
        // 1. Path A: The `PlayerConnection` would quickly load a single song's metadata and
        //    update the UI.
        // 2. Path B: At the same time, the `MusicService` was loading the *entire* queue state
        //    and preparing the player.
        //
        // This created two conflicting sources of truth. The UI would receive inconsistent
        // information (e.g., a "current song" from Path A, but a "song list" from Path B
        // that wasn't ready yet), leading to a crash.
        //
        // The definitive fix is to have a SINGLE source of truth on startup. The `PlayerConnection`
        // must act only as a "dumb listener." It should not load any state itself. It only
        // reflects the state that the `MusicService` provides through the standard `Player.Listener`
        // events after the service has safely and completely initialized.

//        scope.launch {
//            mediaMetadata.value = player.currentMetadata ?: database.getResumptionQueue()?.getCurrentSong()
//            Log.e(TAG, "after database.getResumptionQueue()?.getCurrentSong() ")
//        }


        Log.i(TAG, "PlayerConnection init: Deferring all metadata loading to MusicService to ensure stability.")

    }

    fun playQueue(
        queue: Queue,
        shouldResume: Boolean = false,
        replace: Boolean = true,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        service.playQueue(
            queue = queue,
            shouldResume = shouldResume,
            replace = replace,
            title = title,
            isRadio = isRadio
        )
    }

    /**
     * Add item to queue, right after current playing item
     */
    fun enqueueNext(item: MediaItem) = enqueueNext(listOf(item))

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        service.enqueueNext(items)
    }

    /**
     * Add item to end of current queue
     */
    fun enqueueEnd(item: MediaItem) = enqueueEnd(listOf(item))

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        service.enqueueEnd(items.mapNotNull { it.metadata })
    }

    fun toggleLike() {
        service.toggleLike()
    }

    fun toggleLibrary() {
        service.toggleLibrary()
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        // Ensures the UI receives the new MediaMetadata object (with comments)
        // when MusicService calls replaceMediaItem.
        mediaMetadata.value = player.currentMediaItem?.metadata

        Log.i(TAG, "onTimelineChanged: comment tag= ${mediaMetadata.value?.commentTag}")

        queueWindows.value = player.getQueueWindows()
        queuePlaylistId.value = service.queuePlaylistId
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    /**
     * Shuffles the queue
     */
    fun triggerShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    || !window.isLive()
                    || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive() && window.isDynamic
                    || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }

    fun softKillPlayer() {
        Log.i(TAG, "Stopping player and uninitializing queue")
        player.clearMediaItems()
        service.deInitQueue()
    }

    fun updateMediaMetadata(metadata: com.dd3boh.outertune.models.MediaMetadata) {
        service.updateCurrentMediaMetadata(metadata)
    }

    /**
     * Converts a saved queue into a PlaylistEntity and triggers a batched download.
     * This ensures the queue appears in the Playlist/Downloaded tab with its name.
     */
    fun downloadQueueAsPlaylist(mq: MultiQueueObject) {
        val songs = mq.getCurrentQueueShuffled()
        if (songs.isEmpty()) return

        // 1. Create a unique ID for this queue-based playlist
        val queuePlaylistId = "queue_${mq.id}"

        // 2. Construct the PlaylistEntity using the Queue's title
        // Matches the V20 schema fields we identified earlier
        val playlistEntity = PlaylistEntity(
            id = queuePlaylistId,
            name = mq.title ?: "Queue ${mq.id}",
            browseId = queuePlaylistId,
            thumbnailUrl = songs.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl,
            isEditable = true,
            isLocal = true
        )

        // 3. Call the mapping logic that handles PlaylistSongMap and bookmarkedAt
        service.downloadUtil.downloadCollection(songs, playlistEntity)
    }

}