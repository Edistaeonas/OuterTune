package com.dd3boh.outertune.playback.queues

import android.util.Log
import com.dd3boh.outertune.App
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeAlbumRadio(
    override val playlistId: String,
    override val startShuffled: Boolean = false
) : Queue {
    override val preloadItem: MediaMetadata? = null
    private val endpoint = WatchEndpoint(
        playlistId = playlistId,
        params = "wAEB"
    )
    private var continuation: String? = null

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val albumSongs = YouTube.albumSongs(playlistId).getOrThrow()
        val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
        continuation = nextResult.continuation

        val blacklist = App.instance.database.getAllBlacklistedArtistsSync().map { it.name.lowercase() }.toSet()

        val radioSongs = nextResult.items.subList(albumSongs.size, nextResult.items.size)
            .map { it.toMediaMetadata() }
            .filter { song ->
                val isBlacklisted = song.artists.any { it.name.lowercase() in blacklist }
                if (isBlacklisted) {
                    Log.i("YouTubeAlbumRadio", "Discarding song by blacklisted artist: ${song.title} by ${song.artists.joinToString { it.name }}")
                }
                !isBlacklisted
            }

        Queue.Status(
            title = nextResult.title,
            items = albumSongs.map { it.toMediaMetadata() } + radioSongs,
            mediaItemIndex = nextResult.currentIndex ?: 0
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val nextResult = withContext(IO) {
            YouTube.next(endpoint, continuation).getOrThrow()
        }
        continuation = nextResult.continuation

        val blacklist = withContext(IO) {
            App.instance.database.getAllBlacklistedArtistsSync().map { it.name.lowercase() }.toSet()
        }

        val filteredItems = nextResult.items.map { it.toMediaMetadata() }.filter { song ->
            val isBlacklisted = song.artists.any { it.name.lowercase() in blacklist }
            if (isBlacklisted) {
                Log.i("YouTubeAlbumRadio", "Discarding song by blacklisted artist: ${song.title} by ${song.artists.joinToString { it.name }}")
            }
            !isBlacklisted
        }

        if (filteredItems.isEmpty() && hasNextPage()) {
            return nextPage()
        }

        return filteredItems
    }
}
