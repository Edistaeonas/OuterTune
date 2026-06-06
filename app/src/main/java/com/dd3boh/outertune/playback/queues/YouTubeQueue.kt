package com.dd3boh.outertune.playback.queues

import android.util.Log
import com.dd3boh.outertune.App
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    override val playlistId: String? = endpoint.playlistId,
    override val startShuffled: Boolean = false,
    private var continuation: String? = null,
) : Queue {

    override suspend fun getInitialStatus(): Queue.Status {
        val nextResult = withContext(IO) {
            YouTube.next(endpoint, continuation).getOrThrow()
        }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation

        val blacklist = withContext(IO) {
            App.instance.database.getAllBlacklistedArtistsSync().map { it.name.lowercase() }.toSet()
        }

        val filteredItems = nextResult.items.map { it.toMediaMetadata() }.filter { song ->
            val isBlacklisted = song.artists.any { it.name.lowercase() in blacklist }
            if (isBlacklisted) {
                Log.i("YouTubeQueue", "Discarding song by blacklisted artist: ${song.title} by ${song.artists.joinToString { it.name }}")
            }
            !isBlacklisted
        }

        return Queue.Status(
            title = nextResult.title,
            items = filteredItems,
            mediaItemIndex = nextResult.currentIndex ?: 0
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val nextResult = withContext(IO) {
            YouTube.next(endpoint, continuation).getOrNull()
        }
        if (nextResult != null) {
            endpoint = nextResult.endpoint
        }
        continuation = nextResult?.continuation

        val blacklist = withContext(IO) {
            App.instance.database.getAllBlacklistedArtistsSync().map { it.name.lowercase() }.toSet()
        }

        val filteredItems = nextResult?.items?.map { it.toMediaMetadata() }?.filter { song ->
            val isBlacklisted = song.artists.any { it.name.lowercase() in blacklist }
            if (isBlacklisted) {
                Log.i("YouTubeQueue", "Discarding song by blacklisted artist: ${song.title} by ${song.artists.joinToString { it.name }}")
            }
            !isBlacklisted
        } ?: emptyList()

        // If all items are filtered out, try to get the next page
        if (filteredItems.isEmpty() && hasNextPage()) {
            return nextPage()
        }

        return filteredItems
    }

//    fun getContinuationEndpoint(): String? {
//        return if (endpoint.videoId != null && continuation != null) {
//            "${endpoint.videoId}\n$continuation"
//        } else {
//            null
//        }
//    }

    companion object {
        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
