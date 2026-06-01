package com.dd3boh.outertune.playback.queues

import androidx.compose.ui.geometry.isEmpty
import com.dd3boh.outertune.models.MediaMetadata
import java.util.LinkedList

/**
 * A session-based history cache to prevent song repetition in the Global Radio.
 * This class is not thread-safe and should be accessed from a single coroutine context.
 */
class GlobalRadioHistory {
    private val recentlyPlayed = LinkedList<String>()
    private var capacity = 100

    /**
     * Filters a list of songs, returning only those not present in the recent history.
     */
    fun filter(songs: List<MediaMetadata>): List<MediaMetadata> {
        return songs.filterNot { contains(it) }
    }

    /**
     * Adds a list of songs to the history and trims the list to maintain capacity.
     */
    fun add(songs: List<MediaMetadata>) {
        songs.forEach { song ->
            val key = songToKey(song)
            // Add to the end of the list
            recentlyPlayed.addLast(key)
        }
        // Remove the oldest entries from the front if we are over capacity
        while (recentlyPlayed.size > capacity) {
            recentlyPlayed.removeFirst()
        }
    }

    /**
     * Checks if a song is already in the recent history.
     */
    private fun contains(song: MediaMetadata): Boolean {
        return recentlyPlayed.contains(songToKey(song))
    }

    /**
     * Sets the capacity of the history list. Can only be increased.
     * This is used for the dynamic size rule.
     */
    fun setCapacity(newCapacity: Int) {
        if (newCapacity > this.capacity) {
            this.capacity = newCapacity
        }
    }

    /**
     * Checks if the history is empty, which implies this is the first playlist
     * of the radio session.
     */
    fun isInitialPlaylist(): Boolean {
        return recentlyPlayed.isEmpty()
    }

    /**
     * Creates a unique, consistent key for a song based on its title and primary artist name.
     */
    private fun songToKey(song: MediaMetadata): String {
        return (song.artists.firstOrNull()?.name ?: "Unknown Artist") + "||" + song.title
    }
}