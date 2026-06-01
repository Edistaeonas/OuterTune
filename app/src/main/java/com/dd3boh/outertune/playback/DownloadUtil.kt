package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.compose.foundation.gestures.forEach
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    val TAG = DownloadUtil::class.simpleName.toString()

    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()
//2026.03.09    private val dataSourceFactory = ResolvingDataSource.Factory(
//        CacheDataSource.Factory()
//            .setCache(playerCache)
//            .setUpstreamDataSourceFactory(
//                OkHttpDataSource.Factory(
//                    OkHttpClient.Builder()
//                        .proxy(YouTube.proxy)
//                        .build()
//                )
//            )
    private val dataSourceFactory = ResolvingDataSource.Factory(
        // The upstream for the downloader's resolver is ALWAYS the network.
        // It should NOT have any playerCache or DefaultDataSource layers.
        OkHttpDataSource.Factory(YTPlayerUtils.httpClient)
    ) { dataSpec ->
        val mediaId = dataSpec.key ?: error("No media id")

        // Ensure the downloader always has a valid YouTube session before starting
        if (YouTube.visitorData == null) {
            Log.i(TAG, "Downloader: Session is null, refreshing handshake...")
            runBlocking { YouTube.home() }
        }

        // --- This is the correct and essential logic ---
        val playbackData = runBlocking(Dispatchers.IO) {
            YTPlayerUtils.playerResponseForPlayback(
                mediaId,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            )
        }.getOrThrow()
        val format = playbackData.format

        // This is also critical: save the format details for offline playback.
        database.query {
            upsert(
                FormatEntity(
                    id = mediaId,
                    itag = format.itag,
                    mimeType = format.mimeType.split(";")[0],
                    codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                    bitrate = format.bitrate,
                    sampleRate = format.audioSampleRate,
                    contentLength = format.contentLength!!,
                    loudnessDb = playbackData.audioConfig?.loudnessDb,
                    playbackTrackingUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }

        val streamUrl = playbackData.streamUrl

        dataSpec.buildUpon()
            .setUri(streamUrl.toUri())
            .setKey(mediaId)
            .build()
    }


    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)
    val downloadManager: DownloadManager =
        DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
            maxParallelDownloads = 3
            addListener(
                ExoDownloadService.TerminalStateNotificationHelper(
                    context = context,
                    notificationHelper = downloadNotificationHelper,
                    nextNotificationId = ExoDownloadService.NOTIFICATION_ID + 1
                )
            )
            // ADDED LISTENER: Updates database and enriches metadata when a download completes
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?
                ) {
                    val mediaId = download.request.id

                    // IF REMOVED: Clear the database timestamp so it disappears from the UI immediately
                    if (download.state == Download.STATE_REMOVING || download.state == Download.STATE_FAILED) {
                        CoroutineScope(Dispatchers.IO).launch {
                            Log.i("Edgardebug", "Download removed/failed for $mediaId. Clearing DB status.")
                            database.updateDownloadStatus(mediaId, null)
                            (context as? MusicService)?.runDownloadReport()
                        }
                    }

                    if (download.state == Download.STATE_COMPLETED) {
                        Log.i("Edgardebug", "\nDownload.STATE_COMPLETED for  ${mediaId}")
                        val updateTime = Instant.ofEpochMilli(download.updateTimeMs)
                            .atZone(ZoneOffset.UTC)
                            .toLocalDateTime()

                        CoroutineScope(Dispatchers.IO).launch {
                            // 1. Mark as downloaded in the main song table
//2026.03.09 no! not first!!                            database.updateDownloadStatus(download.request.id, updateTime)

                            // 2. Check if metadata (Artist/Thumb) exists. Flow.firstOrNull() requires the import.
                            val existingSong = database.song(mediaId).firstOrNull()

                            if (existingSong == null || existingSong.artists.isEmpty()) {
                                Log.i("Edgardebug", "Enriching metadata for playlist download: ${mediaId}")

                                // 3. Fetch full metadata from YouTube
                                val result = YTPlayerUtils.playerResponseForMetadata(mediaId)

                                result.onSuccess { playerResponse ->
                                    val videoDetails = playerResponse.videoDetails ?: return@onSuccess

                                    // 4. Construct MediaMetadata using constructor parameters from your specific class
                                    val mediaMetadata = MediaMetadata(
                                        id = videoDetails.videoId,
                                        title = videoDetails.title ?: "",
                                        artists = listOf(MediaMetadata.Artist(
                                            id = videoDetails.channelId,
                                            name = videoDetails.author ?: "Unknown Artist"
                                        )),
                                        album = null,
                                        genre = null,
                                        duration = videoDetails.lengthSeconds?.toInt() ?: 0,
                                        thumbnailUrl = videoDetails.thumbnail?.thumbnails?.lastOrNull()?.url,
                                        isLocal = false
                                    )

                                    database.transaction {
                                        Log.i("Edgardebug", "start of database.transaction for playlist download: ${mediaId} - ${mediaMetadata.title} - ${mediaMetadata.artists} - ${mediaMetadata.album}")
                                        // 1. Insert the metadata (Song/Artist/Album)
                                        database.insert(mediaMetadata)

                                        // 2. Manually ensure Artist-to-Song mapping exists.
                                        mediaMetadata.artists.forEachIndexed { index, artist ->
                                            // Ensure the artist record exists
                                            val artistId = artist.id ?: ("unknown_" + artist.name)
                                            database.insert(ArtistEntity(
                                                id = artistId,
                                                name = artist.name
                                            ))
                                            // Force the link between this song and this artist
                                            database.insert(SongArtistMap(
                                                songId = mediaId,
                                                artistId = artistId,
                                                position = index
                                            ))
                                        }

                                        // 3. Update download status
                                        // Ensure dateDownload isn't overwritten by the insert
                                        database.updateDownloadStatus(mediaId, updateTime)
                                        Log.i("Edgardebug", "end of database.transaction for playlist download: ${mediaId}")
                                    }
                                    // 5. Log the report count
                                    Log.i("Edgardebug", "call runDownloadReport in val downloadManager, DownloadUtil")
                                    (context as? MusicService)?.runDownloadReport()
                                }.onFailure { e ->
                                    Log.e(TAG, "Failed to enrich metadata for playlist song ${mediaId}", e)
                                }
                            } else {
                                // CASE: Song already in DB - just mark it as downloaded
                                Log.i("Edgardebug", "Marking existing song $mediaId as downloaded.\n")
                                database.updateDownloadStatus(mediaId, updateTime)

                            }
                            (context as? MusicService)?.runDownloadReport()
                        }
                    }
                }
            })
        }

    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
    )

    val downloadMgr = DownloadManagerOt(localMgr)
    var isProcessingDownloads = MutableStateFlow(false)

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }



    // Compatibility for single song calls (SongMenu, PlayerMenu)
    fun downloadSingle(song: MediaMetadata) {
        Log.i("Edgardebug", "\n>>>>>>> in DownloadSingle, called for: ${song.title} - id: ${song.id}")
        downloadSong(song.id, song.title, null)
    }

    // 2. NEW UNIQUE FUNCTION: Only for Playlist/Album screens to ensure mapping
//    fun downloadPlaylist(songs: List<MediaMetadata>, playlist: com.dd3boh.outertune.db.entities.PlaylistEntity) {
//        Log.i("Edgardebug", "STARTING PLAYLIST MAPPING: ID=${playlist.id}")
//
//        CoroutineScope(Dispatchers.IO).launch {
//            database.transaction {
//                // ACTIVATION: Force bookmarkedAt so PlaylistsDao.kt filter (line 133) includes it.
//                // We also set isLocal = true as a fallback for the DAO variant filters.
//                val activatedPlaylist = playlist.copy(
//                    bookmarkedAt = java.time.LocalDateTime.now(),
//                    isLocal = true
//                )
//
//                database.insert(activatedPlaylist)
//                database.update(activatedPlaylist) // Force update if already in history
//
//                songs.forEachIndexed { index, song ->
//                    // 1. Insert Song placeholder (Matches your Version 20 schema requirements)
//                    database.insert(com.dd3boh.outertune.db.entities.SongEntity(
//                        id = song.id,
//                        title = song.title,
//                        duration = song.duration,
//                        thumbnailUrl = song.thumbnailUrl,
//                        liked = false,
//                        localPath = null // Placeholder for network cached song
//                    ))
//
//                    // 2. Link song to playlist - Required for the Downloaded tab
//                    database.insert(com.dd3boh.outertune.db.entities.PlaylistSongMap(
//                        playlistId = playlist.id,
//                        songId = song.id,
//                        position = index
//                    ))
//                }
//            }
//
//            Log.i("Edgardebug", "Mapping complete for ${playlist.id}. Starting byte downloads.")
//
//            withContext(Dispatchers.Main) {
//                songs.forEach { song -> downloadSong(song.id, song.title, null) }
//            }
//        }
//    }

    // 1. Rename to 'downloadSongs' - used for selections/menus/individual tracks
    fun downloadSongs(songs: List<MediaMetadata>) {
        Log.i("Edgardebug", "\n>>>>>> in downloadSongs (List) called - Downloading bytes only.")
        songs.forEach { song -> downloadSong(song.id, song.title, null) }
    }

    // 2. The REAL mapping function for Playlists and Albums
// 2026.03.16 batched mapping + Library Activation
    fun downloadCollection(songs: List<MediaMetadata>, playlist: com.dd3boh.outertune.db.entities.PlaylistEntity) {
        Log.i("Edgardebug", "\n>>>>>>  in downloadCollection: STARTING COLLECTION MAPPING: ID=${playlist.id}")

        CoroutineScope(Dispatchers.IO).launch {
            database.transaction {
                // FIX: Force bookmarkedAt. PlaylistsDao line 133 hides everything where this is NULL.
                // We use insert THEN update because your insert strategy is IGNORE.
                val activatedPlaylist = playlist.copy(
                    bookmarkedAt = java.time.LocalDateTime.now(),
                    isLocal = true
                )

                database.insert(activatedPlaylist)
                database.update(activatedPlaylist) // Forces the bookmark date onto existing history

                songs.forEachIndexed { index, song ->
                    // 1. Insert Song placeholder (Satisfies Foreign Key for the Map table)
                    database.insert(com.dd3boh.outertune.db.entities.SongEntity(
                        id = song.id,
                        title = song.title,
                        duration = song.duration,
                        thumbnailUrl = song.thumbnailUrl,
                        liked = false,
                        localPath = null // Required for V20 schema
                    ))

                    // 2. Link song to playlist - This is what makes the tab work
                    database.insert(com.dd3boh.outertune.db.entities.PlaylistSongMap(
                        playlistId = playlist.id,
                        songId = song.id,
                        position = index
                    ))
                }
            }

            Log.i("Edgardebug", "Collection mapping complete for ${playlist.id}")

            withContext(Dispatchers.Main) {
                songs.forEach { song -> downloadSong(song.id, song.title, null) }
            }
        }
    }

    // 2. Main function: Handles mapping and library activation
    // 2026.03.15 Batched mapping with Version 20 schema compatibility
//    fun downloadE(songs: List<MediaMetadata>, playlist: com.dd3boh.outertune.db.entities.PlaylistEntity?) {
//        if (playlist == null) {
//            Log.i("Edgardebug", "Standard download: No playlist context. Just downloading bytes.")
//            songs.forEach { song -> downloadSong(song.id, song.title, null) }
//            return
//        }
//
//        Log.i("Edgardebug", "STARTING BATCH DOWNLOAD: PlaylistID=${playlist.id}")
//        CoroutineScope(Dispatchers.IO).launch {
//            database.transaction {
//                // FIX: Force bookmarkedAt AND isLocal = true.
//                // This satisfies the WHERE clause in PlaylistsDao.kt (line 133)
//                val activatedPlaylist = playlist.copy(
//                    bookmarkedAt = java.time.LocalDateTime.now(),
//                    isLocal = true
//                )
//                database.insert(activatedPlaylist)
//                database.update(activatedPlaylist)
//
//                songs.forEachIndexed { index, song ->
//                    // 1. Insert Song placeholder (Required for Foreign Key link)
//                    // Matches DB Version 20: removed totalPlayTime, added localPath=null
//                    database.insert(com.dd3boh.outertune.db.entities.SongEntity(
//                        id = song.id,
//                        title = song.title,
//                        duration = song.duration,
//                        thumbnailUrl = song.thumbnailUrl,
//                        liked = false,
//                        localPath = null
//                    ))
//
//                    // 2. Link song to playlist
//                    database.insert(com.dd3boh.outertune.db.entities.PlaylistSongMap(
//                        playlistId = playlist.id,
//                        songId = song.id,
//                        position = index
//                    ))
//                }
//            }
//
//            Log.i("Edgardebug", "Database mapping complete for ${playlist.id}")
//
//            withContext(Dispatchers.Main) {
//                songs.forEach { song -> downloadSong(song.id, song.title, null) }
//            }
//        }
//    }

    // 1. Rename to 'downloadIndividualSongs' - used for selections/menus
//    fun downloadIndividualSongs(songs: List<MediaMetadata>) {
//        Log.i("Edgardebug", "Individual download called (No playlist mapping)")
//        songs.forEach { song -> downloadSong(song.id, song.title, null) }
//    }

    // 2. Rename to 'downloadFullPlaylist' - used ONLY for Playlists and Albums
    // 2026.03.16 Force mapping + Force Library Activation
//    fun downloadFullPlaylist(songs: List<MediaMetadata>, playlist: com.dd3boh.outertune.db.entities.PlaylistEntity) {
//        Log.i("Edgardebug", "STARTING PLAYLIST MAPPING: ID=${playlist.id}")
//
//        CoroutineScope(Dispatchers.IO).launch {
//            database.transaction {
//                // ACTIVATION: Force bookmarkedAt date.
//                // PlaylistsDao filter (line 133) hides everything where this is NULL.
//                val activatedPlaylist = playlist.copy(
//                    bookmarkedAt = java.time.LocalDateTime.now(),
//                    isLocal = true
//                )
//
//                database.insert(activatedPlaylist)
//                database.update(activatedPlaylist) // Forces the date onto search history records
//
//                songs.forEachIndexed { index, song ->
//                    // 1. Insert Song placeholder (matches Version 20 schema)
//                    database.insert(com.dd3boh.outertune.db.entities.SongEntity(
//                        id = song.id,
//                        title = song.title,
//                        duration = song.duration,
//                        thumbnailUrl = song.thumbnailUrl,
//                        liked = false,
//                        localPath = null
//                    ))
//
//                    // 2. Map song to playlist
//                    database.insert(com.dd3boh.outertune.db.entities.PlaylistSongMap(
//                        playlistId = playlist.id,
//                        songId = song.id,
//                        position = index
//                    ))
//                }
//            }
//            Log.i("Edgardebug", "Mapping complete for ${playlist.id}. Starting byte downloads.")
//            withContext(Dispatchers.Main) {
//                songs.forEach { song -> downloadSong(song.id, song.title, null) }
//            }
//        }
//    }

    private fun downloadSong(id: String, title: String, playlist: com.dd3boh.outertune.db.entities.PlaylistEntity? = null) {
        Log.i("Edgardebug", "\n>>>>>> in downloadSong (id: $id, title: $title, playlist: $playlist)")
        if (downloads.value[id] != null) return

        // 2. URI FIX: The request URI MUST have a valid scheme (https://) for the Downloader to work.
        val requestUri = android.net.Uri.parse("https://music.youtube.com/watch?v=$id")

        val downloadRequest = DownloadRequest.Builder(id, requestUri)
            .setCustomCacheKey(id)
            .setData(title.toByteArray())
            .build()

        // Use a try-catch here to prevent the "Background Start" crash seen in your logs
        try {
            DownloadService.sendAddDownload(
                context,
                ExoDownloadService::class.java,
                downloadRequest,
                true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start download service: ${e.message}")
        }
    }

    fun resumeDownloadsOnStart() {
        DownloadService.sendResumeDownloads(
            context,
            ExoDownloadService::class.java,
            false
        )
    }


// Deletes from custom dl

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: SongItem) = deleteSong(song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    private fun deleteSong(id: String): Boolean {
        val deleted = localMgr.deleteFile(id)
        if (!deleted) return false
        downloads.update { map ->
            map.toMutableMap().apply {
                remove(id)
            }
        }

        runBlocking {
            database.song(id).first()?.song?.copy(localPath = null)
            database.updateDownloadStatus(id, null)
        }
        return true
    }

    /**
     * Retrieve song from cache, and delete it from cache afterwards
     */
    fun getFromCache(cache: SimpleCache, mediaId: String): ByteArray? {
        val spans: Set<CacheSpan> = cache.getCachedSpans(mediaId)
        if (spans.isEmpty()) return null

        val output = ByteArrayOutputStream()
        try {
            for (span in spans) {
                val file: File? = span.file
                FileInputStream(file).use { fis ->
                    fis.copyTo(output)
                }
            }
            return output.toByteArray()
        } catch (e: IOException) {
            reportException(e)
        } finally {
            output.close()
        }
        return null
    }

    /**
     * Recursively removes all downloaded songs belonging to a playlist/album.
     */
    fun removeCollectionDownload(playlistId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Get all songs mapped to this playlist
            val playlistSongs = database.playlistSongs(playlistId).first()

            Log.i("Edgardebug", "Removing downloads for playlist: $playlistId. Song count: ${playlistSongs.size}")

            playlistSongs.forEach { playlistSong ->
                val songId = playlistSong.song.id

                // 2. Command ExoPlayer to delete the physical bytes
                DownloadService.sendRemoveDownload(
                    context,
                    ExoDownloadService::class.java,
                    songId,
                    false
                )

                // 3. Manually clear the DB status in case the listener is slow
                database.updateDownloadStatus(songId, null)
            }

            // 4. Optionally remove the playlist mapping itself if desired
            // database.clearPlaylist(playlistId)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Downloads removed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Migrated existing downloads from the download cache to the new system in external storage
     */
    suspend fun migrateDownloads() {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true

        var runs = 0
        try {
            // "skeleton" of old download manager to access old download data
            val dataSourceFactory = ResolvingDataSource.Factory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        OkHttpDataSource.Factory(
                            OkHttpClient.Builder()
                                .proxy(YouTube.proxy)
                                .build()
                        )
                    )
            ) { dataSpec ->
                return@Factory dataSpec
            }

            val downloadManager: DownloadManager = DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run)
            ).apply {
                maxParallelDownloads = 3
            }

            // actual migration code
            val downloadedSongs = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                downloadedSongs[cursor.download.request.id] = cursor.download
            }

            // copy all completed downloads
            val toMigrate = downloadedSongs.filter { it.value.state == Download.STATE_COMPLETED }
            toMigrate.forEach { s ->
                if (runs++ % 10 == 0) {
                    Log.i(TAG, "Migrating download: $runs/${toMigrate.size}")
                    if (runs % 20 == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "$runs/${toMigrate.size}", LENGTH_SHORT).show()
                        }
                    }
                }
                val songFromCache = getFromCache(downloadCache, s.key)
                if (songFromCache != null) {
                    downloadCache.removeResource(s.key)
                    downloadMgr.enqueue(
                        mediaId = s.key,
                        data = songFromCache,
                        displayName = runBlocking { database.song(s.key).first()?.title ?: "" })
                }
            }
            scanDownloads()
        } catch (e: Exception) {
            reportException(e)
        } finally {
            isProcessingDownloads.value = false
        }
    }


    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, ""))
        )
    }

    /**
     * Rescan download directory and updates songs
     */
    suspend fun rescanDownloads() {
        Log.i(TAG, "+rescanDownloads()")
        isProcessingDownloads.value = true
        val dbDownloads = database.downloadedOrQueuedSongs().first()
        val result = mutableMapOf<String, LocalDateTime>()

        // get missing files not in custom downloads or in internal downloads, remove them
        val missingFiles =
            localMgr.getMissingFiles(dbDownloads.filterNot { it.song.dateDownload == null }).toMutableList()
        Log.i(TAG, "Found ${missingFiles.size}/${dbDownloads.size} songs not in custom download directories")
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            missingFiles.removeIf { it.id == cursor.download.request.id }
        }
        Log.i(
            TAG,
            "Found ${missingFiles.size}/${dbDownloads.size} song not in custom download directories + internal cache. Removing these files now (if some were found)..."
        )

        database.transaction {
            missingFiles.forEach {
                Log.v(TAG, "Shedding: [${it.id}] ${it.song.title}")
                removeDownloadSong(it.song.id)
            }
        }

        // new files
        val availableDownloads = dbDownloads.minus(missingFiles)
        availableDownloads.forEach { s ->
            result[s.song.id] = s.song.dateDownload!! // sql should cover our butts
        }

        downloads.value = result
        isProcessingDownloads.value = false
        Log.i(TAG, "-rescanDownloads()")
    }


    /**
     * Scan and import downloaded songs from main and extra directories.
     *
     * This is intended for re-importing existing songs (ex. songs get moved, after restoring app backup), thus all
     * songs will already need to exist in the database.
     */
    suspend fun scanDownloads() {
        Log.i(TAG, "+scanDownloads()")
        if (isProcessingDownloads.value) {
            Log.i(TAG, "-scanDownloads()")
            return
        }
        isProcessingDownloads.value = true

//            val scanner = LocalMediaScanner.getScanner(context, ScannerImpl.TAGLIB, SCANNER_OWNER_DL)
        database.removeAllDownloadedSongs()
        val timeNow = LocalDateTime.now()

        // add custom downloads
        val availableFiles = localMgr.getAvailableFiles(false)
        database.transaction {
            availableFiles.forEach { f ->
                try {
                    val file = fileFromUri(context, f.value)
                    if (file == null) throw (InvalidAudioFileException("Hello darkness my old friend"))
                    // TODO: validate files in download folder
//                        val format: FormatEntity? = scanner.advancedScan(f.value).format
//                        if (format != null) {
//                            database.upsert(format)
//                        }
                    registerDownloadSong(f.key, timeNow, file.absolutePath)

                } catch (e: InvalidAudioFileException) {
                    reportException(e)
                }
            }
        }
//            LocalMediaScanner.destroyScanner(SCANNER_OWNER_DL)
        Log.i(TAG, "Registered ${availableFiles.size} files from custom downloads")

        // add internal downloads
        val cursor = downloadManager.downloadIndex.getDownloads()
        var count = 0
        database.transaction {
            while (cursor.moveToNext()) {
                updateDownloadStatus(cursor.download.request.id, stateToLocalDateTime(cursor.download))
                count ++
            }
        }
        Log.i(TAG, "Registered $count files from internal downloads")
        isProcessingDownloads.value = false
        Log.i(TAG, "Database registration complete, triggering map registry rebuild")
        rescanDownloads()
        Log.i(TAG, "-scanDownloads()")
    }

    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        val STATE_INVALID: LocalDateTime = Instant.ofEpochMilli(0).atZone(ZoneOffset.UTC).toLocalDateTime()
    }


    init {
        Log.i(TAG, "DownloadUtil init")
        CoroutineScope(dlCoroutine).launch {
            rescanDownloads()
        }

        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                    // UI State Update
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            val state = stateToLocalDateTime(download)
                            if (state == STATE_INVALID) remove(download.request.id)
                            else set(download.request.id, state)
                        }
                    }

                    if (download.state == Download.STATE_COMPLETED) {
                        val updateTime = java.time.Instant.ofEpochMilli(download.updateTimeMs)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDateTime()

                        CoroutineScope(Dispatchers.IO).launch {
                            val mediaId = download.request.id
                            val existingSong = database.song(mediaId).firstOrNull()

                            if (existingSong == null || existingSong.artists.isEmpty()) {
                                Log.i(TAG, "Enriching metadata for: $mediaId")
                                YTPlayerUtils.playerResponseForMetadata(mediaId).onSuccess { resp ->
                                    val details = resp.videoDetails ?: return@onSuccess
                                    val mediaMetadata = MediaMetadata(
                                        id = details.videoId,
                                        title = details.title ?: "",
                                        artists = listOf(MediaMetadata.Artist(id = details.channelId, name = details.author ?: "Unknown Artist")),
                                        genre = null,
                                        duration = details.lengthSeconds?.toInt() ?: 0,
                                        thumbnailUrl = details.thumbnail?.thumbnails?.lastOrNull()?.url,
                                        isLocal = false
                                    )

                                    database.transaction {
                                        database.insert(mediaMetadata)
                                        database.updateDownloadStatus(mediaId, updateTime)
                                    }
                                    (context as? MusicService)?.runDownloadReport()
                                }
                            } else {
                                // Mark already known songs as downloaded so they show up in tabs
                                Log.i("Edgardebug", "Marking existing song $mediaId as downloaded")
                                database.updateDownloadStatus(mediaId, updateTime)
                            }
                            (context as? MusicService)?.runDownloadReport()
                        }
                    }
                }
            }
        )
    }
}



fun stateToLocalDateTime(download: Download): LocalDateTime {
    return when (download.state) {
        Download.STATE_COMPLETED -> {
            Instant.ofEpochMilli(download.updateTimeMs).atZone(ZoneOffset.UTC).toLocalDateTime()
        }

        Download.STATE_DOWNLOADING, Download.STATE_QUEUED -> STATE_DOWNLOADING
        else -> STATE_INVALID
    }
}