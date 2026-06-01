package com.dd3boh.outertune.models

import android.util.Log
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEachIndexed
import androidx.compose.ui.util.fastSumBy
import androidx.media3.common.C
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * @param title Queue title (and UID)
 * @param queue List of media items
 */
data class MultiQueueObject(
    val id: Long,
    var title: String,
    /**
     * The order of songs are dynamic. This should not be accessed from outside QueueBoard.
     * >>> now we can because the queue object is no longer a MutableList but a SnapshotStateList
     * (Change needed for the Global Artist Radio feature)
     */

    /**
     * The list of songs in the queue.
     * Using `SnapshotStateList` instead of a standard `MutableList` is a critical architectural fix
     * for UI stability. It makes the list directly observable by Jetpack Compose and ensures
     * that all modifications are handled atomically within Compose's snapshot system.
     * This prevents race conditions and `IndexOutOfBoundsException` crashes that can occur when
     * the UI tries to render the list while it's being modified by a background thread.
     */
    val queue: SnapshotStateList<MediaMetadata> = mutableStateListOf(),
    var shuffled: Boolean = false,

    //var queuePos: Int = -1, // position of current song
    // This is the raw value that will be stored in the database.
    // We rename it to avoid conflicts and to make its purpose clear.
    var queuePosState: Int = -1, // position of current song

    var lastSongPos: Long = C.TIME_UNSET,
    var index: Int, // order of queue
    /**
     * Song id to start watch endpoint
     */
    var playlistId: String? = null,
) {
    // This is the new observable property for the UI. It's initialized
    // from the value loaded from the database. The 'by' keyword makes it a State object.
    var queuePos by mutableIntStateOf(queuePosState)

    // The constructor Room will use. It provides the song list separately.
    constructor(
        id: Long,
        title: String,
        songs: MutableList<MediaMetadata>, // This matches the database query
        shuffled: Boolean,
        queuePosState: Int,
        lastSongPos: Long,
        index: Int,
        playlistId: String?
    ) : this(id, title, mutableStateListOf<MediaMetadata>().apply { addAll(songs) }, shuffled, queuePosState, lastSongPos, index, playlistId)


    /**
     * Retrieve the current queue in list form, with shuffle state taken in account
     *
     * @return A copy of the Metadata list
     */
    fun getCurrentQueueShuffled(): MutableList<MediaMetadata> {
        return if (shuffled) {
            val shuffledQueue = ArrayList<MediaMetadata>()
            shuffledQueue.addAll(queue)
            shuffledQueue.sortBy { it.shuffleIndex }
            shuffledQueue
        } else {
            queue
        }
    }

    /**
     * Retrieve the song at current position in the queue
     */
    fun getCurrentSong(): MediaMetadata? {

        // Create stable, local copies of the state at the beginning of the function.
        val currentQueue = this.queue.toList() // .toList() creates an immutable snapshot
        val currentPos = this.queuePos

        // Perform validation on the stable snapshot. This is thread-safe.
        if (currentPos in currentQueue.indices) {
            return currentQueue[currentPos]
        } else {
            Log.w("TRACE", "getCurrentSong: CRASH AVERTED. Index $currentPos is invalid for snapshot size ${currentQueue.size}. Returning null.")
            return null
        }
    }

    /**
     * Retrieve a song given a song ID. Returns null if no song is found
     */
    fun findSong(mediaId: String): MediaMetadata? {
        val currentSong = getCurrentSong()
        if (currentSong?.id == mediaId) {
            return currentSong
        }
        return queue.fastFirstOrNull { it.id == mediaId }
    }

    /**
     * Returns the index of current queue position considering shuffle state
     */
    fun getQueuePosShuffled(): Int {

        // Create stable, local copies to ensure thread safety.
        val currentQueue = this.queue.toList()
        val currentPos = this.queuePos
        val isShuffled = this.shuffled

        if (currentPos in currentQueue.indices) {
            return if (isShuffled) {
                currentQueue[currentPos].shuffleIndex
            } else {
                currentPos
            }
        } else {
            Log.w("TRACE", "getQueuePosShuffled: CRASH AVERTED. Index $currentPos is invalid for snapshot size ${currentQueue.size}. Returning 0.")
            return 0
        }
    }

    fun setCurrentQueuePos(index: Int) {
        if (getQueuePosShuffled() != index) {

            /**
             * queuePos will always track the index of the song in the unsorted queue, *even* if queue is shuffled.
             * To get the real queuePos of the song, look at the shuffleIndex value that equals the index provided
             */
            val newQueuePos = if (shuffled) {
                queue.indexOf(queue.find { it.shuffleIndex == index })
            } else {
                index
            }

            queuePos = newQueuePos
        }
    }

    fun validateQueuePos() {
        // This is the definitive fix for the IndexOutOfBoundsException
        if (queue.isEmpty()) {
            if (queuePos != 0) {
                Log.w("MultiQueueObject", "CRITICAL: Correcting invalid index for an EMPTY queue. Was $queuePos, resetting to 0.")
                queuePos = 0
            }
        } else if (queuePos < 0 || queuePos >= queue.size) {
            Log.w("MultiQueueObject", "CRITICAL: Correcting out-of-bounds index. Was $queuePos, size ${queue.size}. Resetting to 0.")
            queue.fastForEachIndexed { index, s -> s.shuffleIndex = index }
            shuffled = false
            queuePos = 0
        }
    }

    /**
     * Retrieve the total duration of all songs
     *
     * @return Duration in seconds
     */
    fun getDuration(): Int {
        return queue.fastSumBy {
            it.duration // seconds
        }
    }

    /**
     * Get the length of the queue
     */
    fun getSize() = queue.size

    fun replaceAll(mediaList: List<MediaMetadata>) {
        queue.clear()
        queue.addAll(mediaList)
    }
}