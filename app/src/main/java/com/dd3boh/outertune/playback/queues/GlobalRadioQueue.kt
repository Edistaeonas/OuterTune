package com.dd3boh.outertune.playback.queues

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.constants.GlobalRadioStrictFavoritesKey
import com.dd3boh.outertune.constants.GlobalRadioUseLocalKey
import com.dd3boh.outertune.constants.GlobalRadioUseOnlineKey
import com.dd3boh.outertune.constants.GlobalRadioArtistRepeatThresholdKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.SongIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.LinkedList

class GlobalRadioQueue(
    private val context: Context,
    private val db: MusicDatabase,
    // --- NEW CONSTRUCTOR ---
    private val localAndLikedSongLimit: Int,
    private val albumAndArtistLimit: Int,
    private val songsPerArtistLimit: Int
) : Queue {
    private val TAG = "GlobalRadioQueue"

    override val preloadItem: MediaMetadata? = null
    override val playlistId: String? = "GLOBAL_RADIO"
    override val startShuffled: Boolean = false
    private var history: GlobalRadioHistory? = null
    private val recentArtistNames = LinkedList<String>() //  Persistent artist history to avoid cross-batch repeats

    val songHistoryGap = 100 //todo  Default value, can be linked to settings later

    fun setHistory(history: GlobalRadioHistory) {
        this.history = history
    }

    override suspend fun getInitialStatus(): Queue.Status {
        val combinedSongs = buildPlaylist()
        return Queue.Status(
            title = "Global Radio",
            items = shuffleWithArtistConstraint(combinedSongs),
            mediaItemIndex = 0
        )
    }

    override fun hasNextPage(): Boolean = true

    override suspend fun nextPage(): List<MediaMetadata> {
        val combinedSongs = buildPlaylist()
        return shuffleWithArtistConstraint(combinedSongs)
    }

    private fun cleanTitle(title: String): String {
        // Remove text in brackets and parentheses in a song title
        return title
            .replace(Regex("\\s*[(\\[].*?[)\\]]", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase()
    }

    private suspend fun buildPlaylist(): List<MediaMetadata> = coroutineScope {
        Log.i(TAG, "--- Starting GLOBAL RADIO Build ---")

        //val useOnline = context.dataStore.data.map { it[GlobalRadioUseOnlineKey] ?: true }.first()
        //val useLocal = context.dataStore.data.map { it[GlobalRadioUseLocalKey] ?: true }.first()

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val hasInternet = connectivityManager.activeNetwork != null

        val useOnlinePref = context.dataStore.data.map { it[GlobalRadioUseOnlineKey] ?: true }.first()
        val useLocalPref = context.dataStore.data.map { it[GlobalRadioUseLocalKey] ?: true }.first()

        // Online components only run if PREF is on AND INTERNET is actually available
        val shouldAttemptOnline = hasInternet && useOnlinePref
        val useLocal = useLocalPref || !hasInternet // Force local if internet is down


        // --- VARIETY BOOST: Fetch 2x the requested amount to ensure gap can be satisfied ---
        val varietyMultiplier = 2
        val fetchLimitLocal = localAndLikedSongLimit * varietyMultiplier
        val fetchLimitOnline = albumAndArtistLimit * varietyMultiplier


        val deferredResults = mutableListOf<Deferred<List<MediaMetadata>>>()

        if (useLocal) {
            deferredResults.add(async {
                try {
                    getComponentA_LocalSongs(fetchLimitLocal)
                } catch (e: Exception) {
                    Log.e(TAG, "Global Radio: Component A (Local) failed.", e)
                    emptyList<MediaMetadata>() // Return an empty list on failure
                }
            })
            deferredResults.add(async {
                try {
                    getComponentB_LikedSongs(fetchLimitLocal)
                } catch (e: Exception) {
                    Log.e(TAG, "Global Radio: Component B (Liked) failed.", e)
                    emptyList<MediaMetadata>()
                }
            })
        }

        if (shouldAttemptOnline) {
            deferredResults.add(async {
                try {
                    getComponentC_LikedAlbumSongs(fetchLimitOnline)
                } catch (e: Exception) {
                    Log.e(TAG, "Global Radio: Component C (Albums) failed.", e)
                    emptyList<MediaMetadata>()
                }
            })
            deferredResults.add(async {
                try {
                    getComponentD_OnlineRadioSongs(fetchLimitOnline, songsPerArtistLimit)
                } catch (e: Exception) {
                    Log.e(TAG, "Global Radio: Component D (Online) failed.", e)
                    emptyList<MediaMetadata>()
                }
            })
        }

        val allSongs = deferredResults.awaitAll().flatten()

        Log.i(TAG, "--- Deduplication and History Check ---")

        // --- NEW: Persistent History Filter (Title + Artist Gap with Normalization) ---
        val recentPersistentIdentities = withContext(Dispatchers.IO) {
            db.getRecentSongIdentities(songHistoryGap).map {
                val cleanArtist = (it.artistName ?: "Unknown Artist").lowercase()
                val cleanedTitle = cleanTitle(it.title)
                "$cleanArtist||$cleanedTitle"
            }.toSet()
        }

        val filteredByPersistentHistory = allSongs.filterNot { song ->
            val artistName = (song.artists.firstOrNull()?.name ?: "Unknown Artist").lowercase()
            val cleanedTitle = cleanTitle(song.title)
            val key = "$artistName||$cleanedTitle"

            val isRecent = recentPersistentIdentities.contains(key)
            if (isRecent) {
                Log.i(TAG, "Song Gap Filter: Skipping recently played song (normalized match): ${song.title} by ${song.artists.firstOrNull()?.name}")
            }
            isRecent
        }
        // --- END Persistent Filter ---


        Log.i(TAG, "Total songs from all components (pre-filter): ${allSongs.size}")

        // First, remove internal duplicates from this batch
        val uniqueSongs = filteredByPersistentHistory.distinctBy { (it.artists.firstOrNull()?.name?.lowercase() ?: "") + it.title.lowercase() }

        // Now, filter against the session history of previously played songs
        val history = this@GlobalRadioQueue.history
        val finalSongs = if (history != null) {
            val filteredSongs = history.filter(uniqueSongs)
            Log.i(TAG, "Removed ${uniqueSongs.size - filteredSongs.size} songs already in session history.")
            filteredSongs
        } else {
            uniqueSongs
        }

        // Update history with the new, unique songs that will be added to the playlist
        history?.let {
            if (it.isInitialPlaylist()) {
                // For the very first playlist, set the capacity dynamically
                it.setCapacity(finalSongs.size)
            }
            it.add(finalSongs)
        }

        Log.i(TAG, "Final pool count for this batch: ${finalSongs.size}")
        return@coroutineScope finalSongs
    }

    private suspend fun getComponentA_LocalSongs(limit: Int): List<MediaMetadata> = withContext(Dispatchers.IO) {
        // Fetch standard local files (SD card)
        val localEntities = db.getRandomLocalSongEntities(limit)
        // Fetch downloaded YouTube songs (cached items)
        // We query the database for songs marked as downloaded
        val downloadedEntities = db.getRandomDownloadedSongEntities(limit)
        val combinedEntities = (localEntities + downloadedEntities).distinctBy { it.id }.shuffled().take(limit)
        val songs = combinedEntities.mapNotNull { entity -> db.song(entity.id).first() }
        Log.i(TAG, "[Component A] Fetched ${songs.size} local/downloaded songs")
        songs.map { it.toMediaMetadata() }
    }

    private suspend fun getComponentB_LikedSongs(limit: Int): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val songEntities = db.getRandomLikedSongEntities(limit)
        val songs = songEntities.mapNotNull { entity -> db.song(entity.id).first() }
        Log.i(TAG, "[Component B] Fetched ${songs.size} liked songs:")
        songs.forEach { Log.d(TAG, "  - ${it.song.title}") }
        songs.map { it.toMediaMetadata() }
    }

    private suspend fun getComponentC_LikedAlbumSongs(limit: Int): List<MediaMetadata> = withContext(Dispatchers.IO) {
        val likedAlbums = db.getRandomLikedAlbums(limit)
        val songs = likedAlbums.mapNotNull { album ->
            db.getRandomSongFromAlbum(album.album.id)?.let { songEntity ->
                db.song(songEntity.id).first()
            }
        }
        Log.i(TAG, "[Component C] Fetched ${songs.size} songs from ${likedAlbums.size} liked albums:")
        songs.forEach { Log.d(TAG, "  - ${it.song.title}") }
        songs.map { it.toMediaMetadata() }
    }

    private suspend fun getComponentD_OnlineRadioSongs(artistLimit: Int, songsPerArtist: Int): List<MediaMetadata>  = withContext(Dispatchers.IO) {
        val strictFavorites = context.dataStore.data.map { it[GlobalRadioStrictFavoritesKey] ?: false }.first()
        val fullArtistList = db.getMasterArtistList()

        // Filter the master list: Keep online artists OR linked local artists only.
        val masterArtistList = fullArtistList.filter { item ->
            val isLinked = !item.artist.isLocal || !item.artist.channelId.isNullOrEmpty()

            if (!isLinked) {
                // Log specifically when a local artist is skipped due to lack of YouTube link
                Log.i(TAG, "[Component D] Skipping unlinked local artist: '${item.artist.name}' (Radio search would be inaccurate)")
            }
            isLinked
        }

        val excludedCount = fullArtistList.size - masterArtistList.size
        if (excludedCount > 0) {
            Log.i(TAG, "[Component D] Filtered $excludedCount unlinked local artists out of radio rotation.")
        }
        if (masterArtistList.isEmpty()) {
            Log.i(TAG, "[Component D] No linked or online artists in master list. Skipping online radio.")
            return@withContext emptyList()
        }

        val randomArtists = masterArtistList.shuffled().take(artistLimit)
        Log.i(TAG, "[Component D] Selected ${randomArtists.size} artists to fetch from: ${randomArtists.joinToString { it.artist.name }}")

        return@withContext coroutineScope {
            val deferreds = randomArtists.map { artist ->
                async(Dispatchers.IO) {
                    val radioEndpoint = YouTube.search(artist.artist.name, YouTube.SearchFilter.FILTER_ARTIST)
                        .getOrNull()?.items?.firstOrNull { it is ArtistItem }?.let { (it as ArtistItem).radioEndpoint }

                    if (radioEndpoint == null) {
                        Log.w(TAG, "  -> Could not find a radio endpoint for ${artist.artist.name}")
                        return@async emptyList<MediaMetadata>()
                    }

                    val nextResult = YouTube.next(radioEndpoint).getOrNull()
                    var songs = nextResult?.items?.filterIsInstance<SongItem>()?.take(songsPerArtist)?.map {
                        it.toMediaMetadata(parentArtist = artist.artist.name)
                    } ?: emptyList()

                    if (strictFavorites) {
                        val favoriteArtistNames = db.getMasterArtistList().map { it.artist.name }.toSet()
                        songs = songs.filter { song -> song.artists.any { artist -> artist.name in favoriteArtistNames } }
                    }

                    Log.d(TAG, "  -> Fetched ${songs.size} songs for artist: ${artist.artist.name}:")
                    songs.forEach { song -> Log.d(TAG, "    - ${song.title} by ${song.artists.firstOrNull()?.name ?: "Unknown"}") }
                    songs
                }
            }
            val allOnlineSongs = deferreds.awaitAll().flatten()
            Log.i(TAG, "[Component D] Total online radio songs fetched: ${allOnlineSongs.size}")
            allOnlineSongs
        }
    }


    /**
     * Smart shuffle that enforces a gap between songs of the same artist.
     * Uses the persistent 'recentArtistNames' history to ensure variety across batches.
     */
    private suspend fun shuffleWithArtistConstraint(songs: List<MediaMetadata>): List<MediaMetadata> {
        if (songs.isEmpty()) return emptyList()

      // 1. Fetch user preference
        val artistConstraint = context.dataStore.data.map {
            it[GlobalRadioArtistRepeatThresholdKey] ?: 50
        }.first()

        // --- DEFINITIVE FIX: Check TOTAL LIBRARY variety, not just the batch ---
        val totalLibraryArtists = db.getMasterArtistList().size

        // FALLBACK: If this specific batch is too small to satisfy the gap on its own,
        //           we perform a simple shuffle
        if (totalLibraryArtists <= artistConstraint) {
            Log.i(TAG, "Library variety too low for requested gap ($totalLibraryArtists total artists <= $artistConstraint gap). Falling back to simple shuffle.")
            val simpleShuffled = songs.shuffled()

            // Still update the history window so the algorithm stays predictable
            simpleShuffled.forEach { song ->
                song.artists.firstOrNull()?.name?.let { name ->
                    recentArtistNames.add(name.lowercase())
                    if (recentArtistNames.size > artistConstraint) recentArtistNames.removeFirst()
                }
            }

            Log.i(TAG, "--- Final Shuffled Playlist (Simple Shuffle - ${simpleShuffled.size} songs) ---")
            simpleShuffled.forEachIndexed { index, song ->
                val artist = song.artists.firstOrNull()
                // Check if the song artist is the same as the radio artist
                val isSameArtist = song.artists.any { it.name.equals(song.parentArtist, ignoreCase = true) }
                val fromRadioLog = if (song.parentArtist != null && !isSameArtist) " - from \"${song.parentArtist}\" radio" else ""

                Log.i(TAG, "  ${index + 1}. ${artist?.name} (ID: ${artist?.id}) - ${song.title}$fromRadioLog")
            }
            return simpleShuffled
        }

        // 2. SMART SHUFFLE: Enforce the gap strictly using class-level history
        val shuffledPlaylist = mutableListOf<MediaMetadata>()
        val candidateSongs = songs.toMutableList()

        Log.i(TAG, "Smart Shuffle starting: ${songs.size} candidates. Global history size: ${recentArtistNames.size}, Gap: $artistConstraint")

        while (candidateSongs.isNotEmpty()) {

            val validSongs = candidateSongs.filter { song ->
                val name = song.artists.firstOrNull()?.name?.lowercase()
                name == null || name !in recentArtistNames
            }

            val songToAdd = validSongs.randomOrNull()

            if (songToAdd == null) {
                // If we reach here, no remaining candidates can satisfy the gap.
                Log.w(TAG, "Gap reached. Discarding ${candidateSongs.size} remaining songs to preserve artist separation.")
                candidateSongs.forEach { song ->
                    val artist = song.artists.firstOrNull()
                    Log.i(TAG, "  -> Discarded: ${artist?.name} (ID: ${artist?.id}) - ${song.title}")
                }
                break
            }

            shuffledPlaylist.add(songToAdd)
            candidateSongs.remove(songToAdd)

            // Update the persistent history sliding window
            songToAdd.artists.firstOrNull()?.name?.let { name ->
                recentArtistNames.add(name.lowercase())
                if (recentArtistNames.size > artistConstraint) {
                    recentArtistNames.removeFirst()
                }
            }
        }

        Log.i(TAG, "--- Final Shuffled Playlist (Smart Shuffle - ${shuffledPlaylist.size} songs) ---")
        shuffledPlaylist.forEachIndexed { index, song ->
            val artist = song.artists.firstOrNull()
            val paddedIndex = (index + 1).toString().padStart(2, '0')
            val genreString = if (!song.genre.isNullOrEmpty()) {
                song.genre.joinToString { it.title.ifBlank { "unknown" } }
            } else {
                "unknown genre"
            }

            // Check if the song artist is the same as the radio artist
            val isSameArtist = song.artists.any { it.name.equals(song.parentArtist, ignoreCase = true) }
            val fromRadioLog = if (song.parentArtist != null && !isSameArtist) " - from \"${song.parentArtist}\" radio" else ""

            Log.i(TAG, "  $paddedIndex.  - ${artist?.name} (ID: ${artist?.id}) --- ${song.title} - [$genreString]$fromRadioLog")
        }
        Log.i(TAG, "--- End of smart Playlist ---")
        return shuffledPlaylist
    }

}