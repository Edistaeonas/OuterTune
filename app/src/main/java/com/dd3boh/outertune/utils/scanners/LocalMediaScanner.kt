/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.scanners

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.constants.ArtistLinkingSensitivity
import com.dd3boh.outertune.constants.ArtistLinkingSensitivityKey
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.SCANNER_DEBUG
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.ScannerM3uMatchCriteria
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.constants.scannerWhitelistExts
import com.dd3boh.outertune.utils.normalizeForMatching
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongGenreMap
import com.dd3boh.outertune.models.CulmSongs
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.SongTempData
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.ui.utils.ARTIST_SEPARATORS
import com.dd3boh.outertune.ui.utils.STORAGE_ROOT
import com.dd3boh.outertune.utils.cleanTitle
import com.dd3boh.outertune.utils.closestAlbumMatch
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.sanitizeMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset


class LocalMediaScanner(val context: Context, scannerImpl: ScannerImpl) {
    private val TAG = LocalMediaScanner::class.simpleName.toString()
    private var advancedScannerImpl: MetadataScanner = when (scannerImpl) {
        ScannerImpl.TAGLIB -> TagLibScanner()
        ScannerImpl.FFMPEG_EXT -> if (ENABLE_FFMETADATAEX) FFmpegScanner() else TagLibScanner()
        ScannerImpl.MEDIASTORE -> MediaStoreExtractor() // unused
    }

    init {
        Log.d(
            TAG,
            "Creating scanner instance with scannerImpl:  ${advancedScannerImpl.javaClass.name}, requested: $scannerImpl"
        )
    }

    suspend fun advancedScan(
        uri: Uri,
    ): SongTempData {
        val file = fileFromUri(context, uri) ?: throw IOException("Could not access file")
        return advancedScan(file)
    }

    /**
     * Compiles a song with all it's necessary metadata. Unlike MediaStore,
     * this also supports multiple artists, multiple genres (TBD), and a few extra details (TBD).
     */
    suspend fun advancedScan(
        file: File,
    ): SongTempData {
        val path = file.absolutePath
        try {
            if (!file.exists()) throw IOException("File not found")
            if (file.isDirectory) throw InvalidAudioFileException("Path is a directory, not a file: $path")

            // decide which scanner to use
            val ffmpegData =
                if (advancedScannerImpl is TagLibScanner || (ENABLE_FFMETADATAEX && advancedScannerImpl is FFmpegScanner)) {
                    advancedScannerImpl.getAllMetadataFromFile(file)
                } else {
                    throw RuntimeException("Unsupported extractor")
                }

            // --- DETECT FOLDER FALLBACK FOR ALL SCANNERS ---
            var finalAlbumName = ffmpegData.song.song.albumName
            val parentName = file.parentFile?.name

            // If the scanner found no album name, or if the album name exactly matches the parent folder,
            // mark it with § so enrichment knows it's a fallback.

            if (finalAlbumName.isNullOrBlank() || finalAlbumName == parentName) {
                finalAlbumName = "§${parentName ?: "Unknown Album"}"
            }


            // --- DEFINITIVE FIX: Sanitize metadata immediately after extraction ---
            val sanitizedSong = ffmpegData.song.copy(
                song = ffmpegData.song.song.copy(
                    title = sanitizeMetadata(ffmpegData.song.song.title),
                    albumName = sanitizeMetadata(finalAlbumName)
                ),
                artists = ffmpegData.song.artists.map { it.copy(name = sanitizeMetadata(it.name)) },
                album = ffmpegData.song.album?.copy(title = sanitizeMetadata(finalAlbumName))
            )
            return ffmpegData.copy(song = sanitizedSong)
            // --- END FIX ---

        } catch (e: Exception) {
            when (e) {
                is IOException, is IllegalArgumentException, is IllegalStateException -> {
                    if (SCANNER_DEBUG) {
                        e.printStackTrace()
                    }
                    throw InvalidAudioFileException("Failed to access file or not in a playable format: ${e.message} for: $path")
                }

                else -> {
                    if (SCANNER_DEBUG) {
                        Log.w(TAG, "ERROR READING METADATA: ${e.message} for: $path")
                        e.printStackTrace()
                    }

                    // we still want the song to be playable even if metadata extractor fails
                return SongTempData(
                    Song(
                        SongEntity(
                            SongEntity.generateSongId(),
                            sanitizeMetadata(path.substringAfterLast('/')), // Sanitize filename too
                            thumbnailUrl = null,
                            isLocal = true,
                            inLibrary = LocalDateTime.now(),
                            localPath = path
                        ),
                        artists = ArrayList()
                    ),
                    null
                    )
                }
            }
        }

    }

    /**
     * Scan the given scan paths for songs given a list of paths to scan for.
     * This will replace all data in the database for a given song.
     *
     * @param scanPaths List of whitelist paths to scan under. This assumes
     * the current directory is /storage/emulated/0/ a.k.a, /sdcard.
     * For example, to scan under Music and Documents/songs --> ("Music", Documents/songs)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun scanLocal(
        scanPaths: String,
        excludedScanPaths: String,
    ): List<Uri> {
        val songs = ArrayList<Uri>()
        Log.i(TAG, "------------ SCAN: Starting Full Scanner ------------")
        scannerState.value = 1
        scannerProgressProbe.value = 0
        scannerProgressTotal.value = 0
        scannerProgressCurrent.value = -1

        val scanPaths = uriListFromString(scanPaths)
        val excludedScanPaths = uriListFromString(excludedScanPaths)

        getScanFiles(scanPaths, excludedScanPaths, context).forEach { uri ->
            if (SCANNER_DEBUG)
                Log.v(TAG, "PATH: $uri")

            songs.add(uri)
        }

        scannerState.value = 0
        Log.i(TAG, "------------ SCAN: Finished Full Scanner ------------")
        return songs.toList()
    }

    /**
     * Update the Database with local files
     *
     * @param database
     * @param newSongs
     * @param matchStrength How lax should the scanner be
     * @param strictFileNames Whether to consider file names
     * @param refreshExisting Setting this this to true will updated existing songs
     * with new information, else existing song's data will not be touched, regardless
     * whether it was actually changed on disk
     *
     * Inserts a song if not found
     * Updates a song information depending on if refreshExisting value
     */
    suspend fun syncDB(
        database: MusicDatabase,
        newSongs: java.util.ArrayList<SongTempData>,
        matchStrength: ScannerMatchCriteria,
        strictFileNames: Boolean,
        strictFilePaths: Boolean,
        refreshExisting: Boolean = false,
        noDisable: Boolean = false
    ) {
        if (scannerState.value > 0 && scannerState.value != 3) {
            Log.i(TAG, "------------ SYNC: Scanner in use. Aborting Local Library Sync ------------")
            return
        }
        Log.i(TAG, "------------ SYNC: Starting Local Library Sync ------------")
        scannerState.value = 3
        scannerProgressCurrent.value = 0
        scannerProgressProbe.value = 0

        // 1. Initial De-duplication of the incoming songs list
        val finalSongs = ArrayList<SongTempData>()
        if (strictFilePaths || refreshExisting) {
            finalSongs.addAll(newSongs)
        } else {
            newSongs.forEach { song ->
                if (finalSongs.none { s -> compareSong(song.song, s.song, matchStrength, strictFileNames) }) {
                    Log.i(TAG, "------------ SYNC: adding song ${song.song.title} ------------")
                    finalSongs.add(song)
                }
            }
        }
        Log.i(TAG, "Entries to process: ${newSongs.size}. After dedup: ${finalSongs.size}")
        scannerProgressTotal.value = finalSongs.size

        // 2. PERFORMANCE OPTIMIZATION: Create a Map for O(1) lookups
        // This is critical for large libraries.
        val allLocalSongs = database.allLocalDbSongs()
        val songPathMap = allLocalSongs.filter { it.song.localPath != null }
            .associateBy { normalizePathForMap(it.song.localPath) }

        // 3. Batched Processing
        val batchSize = 50
        finalSongs.chunked(batchSize).forEachIndexed { batchIndex, batch ->

            if (scannerRequestCancel) {
                Log.w(TAG, "Scanner canceled during Local Library Sync.")
                throw ScannerAbortException("Scanner canceled during Local Library Sync")
            }

            // Perform entire batch in a single database transaction
            database.withSuspendingTransaction {
                batch.forEachIndexed { itemIndex, song ->
                    val totalProcessed = (batchIndex * batchSize) + itemIndex + 1
                    scannerProgressCurrent.value = totalProcessed

                    if (SCANNER_DEBUG && totalProcessed % 100 == 0) {
                        Log.i(TAG, "------------ SYNC: Local Library Sync: $totalProcessed/${finalSongs.size} processed ------------")
                    }

                    // Look up song by path in our optimized Map
                    val songMatch = song.song.song.localPath?.let {
                        songPathMap[normalizePathForMap(it)]
                    }

                    if (songMatch != null) { // KNOWN SONG
                        // Protect Manual Links logic
                        val oldArtistId = songMatch.artists.firstOrNull()?.id
                        val oldArtist = if (oldArtistId != null) artistById(oldArtistId) else null

                        if (oldArtist != null && !oldArtist.channelId.isNullOrEmpty()) {
                            val songToUpdate = song.song.song.copy(id = songMatch.song.id, localPath = song.song.song.localPath)
                            if (refreshExisting || songMatch.song.inLibrary == null) {
                                update(songToUpdate)
                                if (song.format != null) {
                                    upsert(song.format.copy(id = songToUpdate.id))
                                }
                            }
                            return@forEachIndexed
                        }

                        val oldSong = songMatch.song
                        val songToUpdate = song.song.song.copy(id = oldSong.id, localPath = song.song.song.localPath)

                        if (!refreshExisting && (oldSong.inLibrary == null || oldSong.localPath == null)) {
                            update(songToUpdate)
                            if (song.format != null) {
                                upsert(song.format.copy(id = songToUpdate.id))
                            }
                        }

                        if (!refreshExisting) {
                            if (oldSong.localPath != songToUpdate.localPath && oldSong.inLibrary != null) {
                                // Calls updateLocalSongPath safely
                                updateLocalSongPath(songToUpdate.id, songToUpdate.inLibrary, songToUpdate.localPath)
                            }
                            return@forEachIndexed
                        }

                        // Full refresh logic: update metadata, albums, genres, and link artists
                        val artistsToDo = ArrayList<Pair<ArtistEntity?, ArtistEntity>>()
                        song.song.artists.forEach { artistsToDo.add(Pair(artistByName(it.name), it)) }
                        val resolvedArtistIds = artistsToDo.map { it.first?.id ?: it.second.id }.toSet()

                        val genreToDo = ArrayList<Pair<GenreEntity?, GenreEntity>>()
                        song.song.genre?.forEach { genreToDo.add(Pair(genreByNameFuzzy(it.title).firstOrNull(), it)) }

                        var albumToDo: Pair<AlbumEntity?, AlbumEntity>? = null

                        song.song.album?.let {
                                scannedAlbum ->
                            Log.i("Edgardebug", "SYNC: Processing song '${song.song.song.title}' with tag album '${scannedAlbum.title}'")
                            //   Log potential fuzzy matches for debugging purposes only
                            val dbQuery = database.localAlbumsByNameFuzzy(scannedAlbum.title).sortedBy { it.title.length }
                            val fuzzyMatch = closestAlbumMatch(scannedAlbum.title, dbQuery)
                            if (fuzzyMatch != null) {
                                val fuzzyArtists = database.getAlbumArtistIds(fuzzyMatch.id)
                                Log.w("Edgardebug", "SYNC: REJECTED fuzzy match (old flawed logic): Tag says '${scannedAlbum.title}', Fuzzy matched '${fuzzyMatch.title}' by artist IDs $fuzzyArtists. .")
                            }


                            // 1. Get potential albums with exactly this name
                            val potentialAlbums = database.albumsByName(scannedAlbum.title)

                            // 2. Only match if the existing album shares an artist with the current song
                            // FIX: Use resolvedArtistIds instead of the raw scanned artist IDs
                            val correctAlbum = potentialAlbums.firstOrNull { existingAlbum ->
                                val existingAlbumArtistIds = database.getAlbumArtistIds(existingAlbum.id)
                                existingAlbumArtistIds.any { it in resolvedArtistIds }
                            }

                            if (correctAlbum != null) {
                                Log.i("Edgardebug", "SYNC: Found EXACT match for album '${scannedAlbum.title}' by artist(s). Merging.")
                                albumToDo = Pair(correctAlbum, scannedAlbum)
                            } else {
                                // No safe match found - create a NEW album entry for this artist
                                Log.i("Edgardebug", "No artist match found for album '${scannedAlbum.title}'. Creating new album entry.")
                                albumToDo = Pair(null, scannedAlbum)
                            }
                        }

                        val finalAlbumId = albumToDo?.first?.id ?: albumToDo?.second?.id
                        val finalAlbumName = albumToDo?.first?.title ?: albumToDo?.second?.title
                        Log.i("Edgardebug", "SYNC: Song '${song.song.song.title}' -> Database Result: Album ID: $finalAlbumId, Name: $finalAlbumName")


                        update(songToUpdate.copy(
                            albumId = albumToDo?.first?.id ?: albumToDo?.second?.id,
                            albumName = albumToDo?.first?.title ?: albumToDo?.second?.title
                        ))
                        if (song.format != null) upsert(song.format.copy(id = songToUpdate.id))

                        unlinkSongArtists(songToUpdate.id)
                        unlinkSongAlbums(songToUpdate.id)
                        unlinkSongGenres(songToUpdate.id)

                        artistsToDo.forEachIndexed { idx, item ->
                            val finalArtistId = item.first?.id ?: item.second.id
                            if (item.first == null) {
                                insert(item.second)
                            }
                            insert(SongArtistMap(songToUpdate.id, finalArtistId, idx))
                        }

                        genreToDo.forEachIndexed { idx, item ->
                            if (item.first == null) {
                                insert(item.second)
                                insert(SongGenreMap(songToUpdate.id, item.second.id, idx))
                            } else {
                                insert(SongGenreMap(songToUpdate.id, item.first!!.id, idx))
                            }
                        }

                        albumToDo?.let { album ->
                            val finalAlbumId = album.first?.id ?: album.second.id
                            if (album.first == null) {
                                insert(album.second)
                                // FIX: Link the new album to its artists so future scans can find it
                                resolvedArtistIds.forEachIndexed { idx, artistId ->
                                    insert(com.dd3boh.outertune.db.entities.AlbumArtistMap(finalAlbumId, artistId, idx))
                                }
                                insert(SongAlbumMap(songToUpdate.id, finalAlbumId, 0))
                            } else {
                                update(album.first!!.copy(
                                    thumbnailUrl = album.second.thumbnailUrl,
                                    songCount = album.first!!.songCount + 1
                                ))
                                insert(SongAlbumMap(songToUpdate.id, finalAlbumId, album.first!!.songCount))
                            }
                        }
                    } else { // NEW SONG
                        insert(song.song.toMediaMetadata())
                        song.format?.let { upsert(it.copy(id = song.song.id)) }
                    }
                }
            }
        }

        scannerProgressCurrent.value = scannerProgressTotal.value
        if (!noDisable) {
            disableSongs(finalSongs.map { it.song }, database)
        }


        scannerState.value = 0
        Log.i(TAG, "------------ SYNC: Finished Local Library Sync ------------")
    }




    /**
     * A faster scanner implementation that adds new songs to the database,
     * and does not touch older songs entries (apart from removing
     * inacessable songs from libaray).
     *
     * No remote artist lookup is done
     *
     * WARNING: cachedDirectoryTree is not refreshed and may lead to inconsistencies.
     * It is highly recommend to rebuild the tree after scanner operation
     *
     * @param newSongs List of songs. This is expecting a barebones DirectoryTree
     * (only paths are necessary), thus you may use the output of refreshLocal().toList()
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun quickSync(
        database: MusicDatabase,
        newSongs: List<Uri>,
        matchCriteria: ScannerMatchCriteria,
        strictExtensions: Boolean,
        strictFilePaths: Boolean,
        noDisable: Boolean = false
    ): ArrayList<SongTempData> {
        Log.i(TAG, "------------ SYNC: Starting Quick (additive delta) Library Sync ------------")
        scannerState.value = 2 // State: Scanning

        val allKnownPaths = database.allLocalSongs().mapNotNull { it.song.localPath }.toSet()
        val allPassedPaths = newSongs.mapNotNull { absoluteFilePathFromUri(context, it) }
        val delta = allPassedPaths.minus(allKnownPaths).toList()
        val allProcessedSongs = ArrayList<SongTempData>()

        Log.i(TAG, "Total songs passed: ${newSongs.size}, Known songs: ${allKnownPaths.size}, New songs to process (delta): ${delta.size}")
        scannerProgressTotal.value = delta.size
        scannerProgressCurrent.value = 0

        // no need now because it is runScanner is calling finalize now.
//        if (delta.isEmpty()) {
//            Log.i(TAG, "No new songs found. Proceeding to finalization.")
//            scannerState.value = 3
//            disableSongsByPath(allPassedPaths, database)
//            finalize(database, allProcessedSongs )
//            scannerState.value = 0
//            Log.i(TAG, "------------ SYNC: Finished Quick (additive delta) Library Sync (no new songs) ------------")
//            return allProcessedSongs
//        }

        val batchSize = 100

        delta.chunked(batchSize).forEach { batch ->
            if (scannerRequestCancel) {
                Log.i(TAG, "WARNING: Requested to cancel. Aborting.")
                throw ScannerAbortException("Scanner canceled during Quick (additive delta) Library Sync")
            }

            val finalSongsInBatch = ArrayList<SongTempData>()
            val scannerJobs = batch.map { path ->
                CoroutineScope(lmScannerCoroutine).async {
                    try {
                        advancedScan(File(path))
                    } catch (e: InvalidAudioFileException) {
                        null
                    }
                }
            }

            scannerJobs.awaitAll().forEach { songData ->
                songData?.let {
                    finalSongsInBatch.add(it)
                    allProcessedSongs.add(it)
                }
            }

            scannerProgressCurrent.value += batch.size

            if (finalSongsInBatch.isNotEmpty()) {
                // --- FIX: Temporarily set state to idle to allow syncDB to run ---
                val previousState = scannerState.value
                scannerState.value = 0
                syncDB(database, finalSongsInBatch, matchCriteria, strictExtensions, strictFilePaths, noDisable = true)
                scannerState.value = previousState // Restore state
                // --- END FIX ---
            }
        }

        // Finalization after all batches
        Log.i(TAG, "All batches synced. Starting finalization...")
        scannerState.value = 3
        // Only disable songs if noDisable is false ---
        if (!noDisable) {
            disableSongsByPath(allPassedPaths, database)
        } else {
            Log.i(TAG, "Automatic scan: Skipping disableSongsByPath to prevent data loss.")
        }
        //finalize(database)  moved elsewhere

        scannerState.value = 4
        Log.i(TAG, "------------ SYNC: Finished Quick (additive delta) Library Sync ------------")
        return allProcessedSongs
    }


    /**
     * A new, "smart" scanner that finds only new files but processes them fully.
     * It performs a deep metadata scan and database sync only on files not already in the library.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun smartSyncNewFiles(
        database: MusicDatabase,
        newSongs: List<Uri>,
        matchCriteria: ScannerMatchCriteria,
        strictExtensions: Boolean,
        strictFilePaths: Boolean
    ): ArrayList<SongTempData> {
        Log.i(TAG, "------------ SYNC: Starting Smart Sync (New Files Only) ------------")
        scannerState.value = 2 // State: Processing

        val allKnownPaths = database.allLocalSongs().mapNotNull { it.song.localPath }.toSet()
        val allPassedPaths = newSongs.mapNotNull { absoluteFilePathFromUri(context, it) }
        val delta = allPassedPaths.minus(allKnownPaths).toList()
        val allProcessedSongs = ArrayList<SongTempData>()

        Log.i(TAG, "Total songs passed: ${newSongs.size}, Known songs: ${allKnownPaths.size}, New songs to process (delta): ${delta.size}")
        scannerProgressTotal.value = delta.size
        scannerProgressCurrent.value = 0

        if (delta.isEmpty()) {
            Log.i(TAG, "No new songs found for Smart Sync.")
            // Don't set scannerState to 0 here, let the calling function handle final state.
            return allProcessedSongs
        }

        val batchSize = 100

        delta.chunked(batchSize).forEach { batch ->
            if (scannerRequestCancel) {
                Log.w(TAG, "Scanner canceled during Smart Sync. Aborting.")
                throw ScannerAbortException("Scanner canceled during Smart Sync")
            }

            val finalSongsInBatch = ArrayList<SongTempData>()
            val scannerJobs = batch.map { path ->
                CoroutineScope(lmScannerCoroutine).async {
                    try {
                        advancedScan(File(path))
                    } catch (e: InvalidAudioFileException) {
                        null
                    }
                }
            }

            scannerJobs.awaitAll().forEach { songData ->
                songData?.let {
                    finalSongsInBatch.add(it)
                    allProcessedSongs.add(it)
                }
            }

            scannerProgressCurrent.value += batch.size

            if (finalSongsInBatch.isNotEmpty()) {
                // Use syncDB with refreshExisting = true to ensure new songs get full metadata.
                // Crucially, noDisable is true to prevent it from touching existing songs.
                syncDB(
                    database,
                    finalSongsInBatch,
                    matchCriteria,
                    strictExtensions,
                    strictFilePaths,
                    refreshExisting = true, // Process these new files fully
                    noDisable = true      // Do NOT disable any other files in the library
                )
            }
        }

        Log.i(TAG, "------------ SYNC: Finished Smart Sync (New Files Only) ------------")
        return allProcessedSongs
    }


    /**
     * Run a full scan and ful database update. This will update all song data in the
     * database of all songs, and also disable inacessable songs
     *
     * No remote artist lookup is done
     *
     * WARNING: cachedDirectoryTree is not refreshed and may lead to inconsistencies.
     * It is highly recommend to rebuild the tree after scanner operation
     *
     * @param newSongs List of songs. This is expecting a barebones DirectoryTree
     * (only paths are necessary), thus you may use the output of refreshLocal().toList()
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fullSync(
        database: MusicDatabase,newSongs: List<Uri>,
        matchCriteria: ScannerMatchCriteria,
        strictExtensions: Boolean,
        strictFilePaths: Boolean,
    ): ArrayList<SongTempData> {
        Log.i(TAG, "------------ SYNC: Starting FULL Library Sync ------------")
        Log.i(TAG, "Entries to process: ${newSongs.size}")
        scannerState.value = 2 // State: Scanning

        val allPassedPaths = newSongs.mapNotNull { absoluteFilePathFromUri(context, it) }
        scannerProgressTotal.value = allPassedPaths.size
        scannerProgressCurrent.value = 0

        val batchSize = 100
        val allScannedSongs = arrayListOf<Song>()
        val allProcessedSongData = ArrayList<SongTempData>()

        allPassedPaths.chunked(batchSize).forEach { batch ->
            if (scannerRequestCancel) {
                Log.i(TAG, "WARNING: Requested to cancel. Aborting.")
                throw ScannerAbortException("Scanner canceled during FULL Library Sync")
            }

            val finalSongsInBatch = ArrayList<SongTempData>()
            val scannerJobs = batch.map { path ->
                CoroutineScope(lmScannerCoroutine).async {
                    try {
                        advancedScan(File(path))
                    } catch (e: InvalidAudioFileException) {
                        null
                    }
                }
            }

            scannerJobs.awaitAll().forEach { songData ->
                songData?.let {
                    finalSongsInBatch.add(it)
                    allScannedSongs.add(it.song)
                    allProcessedSongData.add(it)
                }
            }

            scannerProgressCurrent.value += batch.size

            if (finalSongsInBatch.isNotEmpty()) {
                // --- FIX: Temporarily set state to idle to allow syncDB to run ---
                val previousState = scannerState.value
                scannerState.value = 0
                syncDB(database, finalSongsInBatch, matchCriteria, strictExtensions, strictFilePaths, refreshExisting = true, noDisable = true)
                scannerState.value = previousState // Restore state
                // --- END FIX ---
            }
        }

        // Finalization after all batches
        Log.i(TAG, "All batches synced. Starting finalization...")
        scannerState.value = 3
        disableSongs(allScannedSongs, database)
        //finalize(database)  moved elsewhere

        scannerState.value = 4
        Log.i(TAG, "------------ SYNC: Finished FULL Library Sync ------------")
        return allProcessedSongData
    }

    /**
     * Run a full scan using Android's MediaStore. This will update all song data in the
     * database for all songs found in the specified paths, and also disable inaccessible songs.
     *
     * This is the preferred method for modern Android as it is generally faster and more reliable.
     *
     * @param database The application's database instance.
     * @param scanPaths List of `Uri`s to include in the scan.
     * @param excludedScanPaths List of `Uri`s to exclude from the scan.
     * @param matchCriteria How to determine if a song is a duplicate (not currently used by MediaStore scan).
     * @param strictFilePaths If true, treat files with the same metadata but different paths as unique songs.
     * @param refreshExisting If true, re-scan and overwrite metadata for songs already in the database.
     */

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun fullMediaStoreSync(
        database: MusicDatabase,
        scanPaths: List<Uri>,
        excludedScanPaths: List<Uri>,
        matchCriteria: ScannerMatchCriteria,
        strictFilePaths: Boolean,
        refreshExisting: Boolean,
        isAutomaticScan: Boolean = false
    ) : ArrayList<SongTempData> {
        Log.i(TAG, "------------ SYNC: Starting MediaStore FULL Library Sync, refreshExisting = $refreshExisting ------------")
        scannerState.value = 2
        scannerProgressCurrent.value = 0
        scannerProgressProbe.value = 0

        val projection = arrayListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.COMPOSER,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.BITRATE)
                if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 15) {
                    add(MediaStore.Audio.Media.BITS_PER_SAMPLE)
                }
                add(MediaStore.Audio.Media.GENRE)
                add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                add(MediaStore.Audio.Media.DISC_NUMBER)
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
            }
        }

        val mediaStoreSongs = ArrayList<SongTempData>()
        val allowedPathPrefixes = scanPaths.mapNotNull { absoluteFilePathFromUri(context, it) }
        val excludedPathPrefixes = excludedScanPaths.mapNotNull { absoluteFilePathFromUri(context, it) }

        val contentResolver: ContentResolver = context.contentResolver
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection.toTypedArray(),
            selection,
            null,
            null
        )

        var logCounter = 0
        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val pathColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durationColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val artistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val yearColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val dateModifiedColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val composerColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER)

            var bitrateColumn: Int? = null
            var bitsPerSampleColumn: Int? = null
            var genreColumn: Int? = null
            var trackNumberColumn: Int? = null
            var discNumberColumn: Int? = null
            var albumArtistColumn: Int? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitrateColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)
                if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 15) {
                    bitsPerSampleColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.BITS_PER_SAMPLE)
                }
                genreColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                trackNumberColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                discNumberColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISC_NUMBER)
                albumArtistColumn = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            }

            while (c.moveToNext()) {
                val rawPath = c.getString(pathColumn)
                if (rawPath == null) {
                    continue
                }

                val cleanPath = rawPath.removeSuffix("/")

                // --- DEFINITIVE FIX: Filter paths in memory ---
                val isAllowed = allowedPathPrefixes.any { cleanPath.startsWith(it) }
                val isExcluded = excludedPathPrefixes.any { cleanPath.startsWith(it) }

                if (!isAllowed || isExcluded) {
                    continue // This is the line that skips files not in your chosen folders.
                }

                // --- Log ONLY the files we are actually processing ---
                if (logCounter % 100 == 0) {
                    Log.d("FolderScan", "Processing path: '$cleanPath'")
                }
                logCounter++


                // If we get here, the song is valid and should be processed.
                val id = c.getLong(idColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

//                var title: String? = c.getString(titleColumn)
//                var artist: String? = c.getString(artistColumn)
//                var album: String? = c.getString(albumColumn)

                // --- SANITIZE STRINGS FROM MEDIASTORE ---
                val rawTitleFromProvider = c.getString(titleColumn)
                var title = rawTitleFromProvider?.let { sanitizeMetadata(it) }
                if (rawTitleFromProvider != null && title != null && rawTitleFromProvider != title) {
                    Log.i("MetadataSanitize", "Title modified: '$rawTitleFromProvider' -> '$title'")
                }

                val artistRaw = c.getString(artistColumn)?.let { sanitizeMetadata(it) }
                var albumRaw = c.getString(albumColumn)?.let { sanitizeMetadata(it) }

                // --- NEW: Detect if MediaStore used the folder name as a fallback ---
                val parentFolderName = File(cleanPath).parentFile?.name
                if (albumRaw != null && albumRaw == parentFolderName) {
                    albumRaw = "§$albumRaw"
                    Log.d("FolderScan", "MediaStore fallback detected!!!!!!!!! Marking album as: '$albumRaw'")
                }

                if (title.isNullOrBlank()) {
                    val rawName = c.getString(nameColumn)?.substringBeforeLast('.')
                    title = rawName?.let { sanitizeMetadata(it) } ?: "Unknown Title"
                    if (rawName != null && title != "Unknown Title" && rawName != title) {
                        Log.d("MetadataSanitize", "Title (from filename) modified: '$rawName' -> '$title'")
                    }
                }

                val duration = c.getInt(durationColumn) / 1000
                val rawYear = c.getString(yearColumn)
                val rawDateModified = c.getString(dateModifiedColumn)
                val name = c.getString(nameColumn)
                val mime = c.getString(mimeColumn)
                val composerRaw = c.getString(composerColumn)?.let { sanitizeMetadata(it) }

                var bitrate: Int? = null
                var bitsPerSample: Int? = null
                var genre: String? = null
                var trackNumber: Int? = null
                var discNumber: Int? = null
                var albumArtistRaw: String? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    bitrateColumn?.let { bitrate = c.getInt(it) }
                    if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 15) {
                        bitsPerSampleColumn?.let { bitsPerSample = c.getInt(it) }
                    }
                    genreColumn?.let { genre = c.getString(it) }
                    trackNumberColumn?.let { trackNumber = c.getInt(it) }
                    discNumberColumn?.let { discNumber = c.getInt(it) }
                    albumArtistColumn?.let { albumArtistRaw = c.getString(it) }
                }
                // Sanitize the metadata
                val albumArtist = albumArtistRaw?.let { sanitizeMetadata(it) }

                val year = rawYear?.toIntOrNull()
                var dateModified: LocalDateTime? = null
                try {
                    rawDateModified?.toLongOrNull()?.let {
                        dateModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                val artistList = ArrayList<ArtistEntity>()
                val genresList = ArrayList<GenreEntity>()
//                artist?.split(ARTIST_SEPARATORS)?.forEach { artistVal ->
//                    artistList.add(ArtistEntity(ArtistEntity.generateArtistId(), artistVal, isLocal = true))
//                }
                artistRaw?.split(ARTIST_SEPARATORS)?.forEach { artistVal ->
                    artistList.add(ArtistEntity(ArtistEntity.generateArtistId(), artistVal, isLocal = true))
                }

                // --- ADD THIS LOG TO VERIFY MULTI-ARTIST SONGS ---
                if (artistList.size > 1) {
                    Log.d(
                        "MultiArtistDebug",
                        "Song '${title ?: name}' has multiple artists. Original string: '$artistRaw'. Parsed artists: ${artistList.map { it.name }}"
                    )
                }
                // --- END LOG ---
                genre?.split(ARTIST_SEPARATORS)?.forEach { genreVal ->
                    genresList.add(GenreEntity(GenreEntity.generateGenreId(), genreVal, isLocal = true))
                }

                val albumID = AlbumEntity.generateAlbumId()
                val albumEntity = if (albumRaw != null) AlbumEntity(
                    id = albumID,
                    title = albumRaw,
                    thumbnailUrl = cleanPath,
                    songCount = 1,
                    duration = duration,
                    isLocal = true
                ) else null

                mediaStoreSongs.add(
                    SongTempData(
                        Song(
                            song = SongEntity(
                                id = SongEntity.generateSongId(),
                                title = title!!,
                                duration = duration,
                                thumbnailUrl = cleanPath,
                                inLibrary = LocalDateTime.now(),
                                isLocal = true,
                                localPath = cleanPath,
                                trackNumber = trackNumber,
                                discNumber = discNumber,
                                albumId = albumID,
                                albumName = albumRaw,
                                albumArtist = albumArtist,
                                year = year,
                                dateModified = dateModified,
                                composer = composerRaw,
                            ),
                            artists = artistList,
                            album = albumEntity,
                            genre = genresList
                        ),
                        FormatEntity(
                            id = id.toString(),
                            itag = -1,
                            mimeType = mime,
                            codecs = mime?.substringAfter('/') ?: "",
                            bitrate = bitrate ?: -1,
                            sampleRate = bitsPerSample,
                            contentLength = duration.toLong(),
                            loudnessDb = null,
                        )
                    )
                )
            }
        }

        Log.i(TAG, "MediaStore query finished. Found ${mediaStoreSongs.size} valid songs.")

        val finalSongs = if (!refreshExisting) {
            val allSongs = database.allLocalSongs().mapNotNull { it.song.localPath }.toSet()
            ArrayList(mediaStoreSongs.filterNot { it.song.song.localPath in allSongs })
        } else {
            mediaStoreSongs
        }

        scannerProgressTotal.value = finalSongs.size

        if (finalSongs.isNotEmpty()) {
            val previousState = scannerState.value
            scannerState.value = 0
            syncDB(
                database, finalSongs, matchCriteria,
                false,
                strictFilePaths,
                refreshExisting = refreshExisting, noDisable = true
            )
            scannerState.value = previousState
        } else {
            Log.i(TAG, "Not syncing, no valid songs found after delta check!")
        }

        scannerState.value = 3
        // --- DEFINITIVE FIX: Only disable songs if it's a manual scan ---
        if (!isAutomaticScan) {
            disableSongsByPath(mediaStoreSongs.mapNotNull { it.song.song.localPath }, database)
        } else {
            Log.i(TAG, "Automatic scan: Skipping disableSongsByPath to prevent data loss.")
        }
        //finalize(database) ===> can't call it now, the database is not ready with all the new artists
        scannerState.value = 4

        Log.i(TAG, "------------ SYNC: Finished MediaStore FULL Library Sync ------------")

        return finalSongs
    }


    private fun normalizePathForMap(path: String?): String {
        if (path == null) return ""
        // Remove redundant slashes and lowercase for case-insensitive matching
        return path.replace("//", "/").removeSuffix("/").lowercase()
    }


    private suspend fun disableSongsByPath(newSongs: List<String>, database: MusicDatabase) {
        Log.i(TAG, "Start optimized finalize (disableSongsByPath). Valid songs count: ${newSongs.size}")

        // 1. Create a Set for O(1) lookup speed instead of O(N)
        val validPathSet = newSongs.toHashSet()

        // 2. Clear invalid paths from the database first
        database.disableInvalidLocalSongs()

        // 3. Filter songs in memory to find which ones are no longer in valid folders
        val allSongs = database.allLocalSongs()
        val songsToDisable = allSongs.filter { it.song.localPath != null && it.song.localPath !in validPathSet }

        if (songsToDisable.isNotEmpty()) {
            Log.i(TAG, "Disabling ${songsToDisable.size} songs that are no longer in valid folders.")
            // 4. Wrap all updates in a single transaction for maximum speed
            database.withSuspendingTransaction {
                songsToDisable.forEach { song ->
                    disableLocalSong(song.song.id)
                }
            }
        }

        Log.i(TAG, "Finished (disableSongsByPath) job")
    }

    private suspend fun disableSongs(newSongs: List<Song>, database: MusicDatabase) {
        Log.i(TAG, "Start optimized finalize (disableSongs). Valid songs count: ${newSongs.size}")

        // 1. Create a Set of valid paths for instant lookup
        val validPathSet = newSongs.mapNotNull { it.song.localPath }.toHashSet()

        // 2. Identify songs in DB that are no longer present in the scan
        val allSongsInDb = database.allLocalSongs()
        val songsToDisable = allSongsInDb.filter { it.song.localPath != null && it.song.localPath !in validPathSet }

        if (songsToDisable.isNotEmpty()) {
            Log.i(TAG, "Disabling ${songsToDisable.size} songs.")
            // 3. Batch disable in a single transaction
            database.withSuspendingTransaction {
                songsToDisable.forEach { song ->
                    disableLocalSong(song.song.id)
                }
            }
        }
        Log.i(TAG, "Finished (disableSongs) job")
    }



    suspend fun logFinalArtistState(database: MusicDatabase) {
        Log.i("ArtistLinkDebug", "--- FINAL ARTIST LIBRARY STATE ---")
        val allArtists = database.allArtistsRaw()
        val allSongs = database.allLocalSongs()

        if (allArtists.isEmpty()) {
            Log.w("ArtistLinkDebug", "Database contains no artists.")
            return
        }

        allArtists.forEach { artist ->
            val songCount = allSongs.count { it.artists.any { a -> a.id == artist.id } }
            val isLinked = !artist.isLocal || !artist.channelId.isNullOrEmpty()
            val linkedStatus = if (isLinked) "YouTube Match" else "No YouTube Match"

            val message = "Artist: '${artist.name}' (ID: ${artist.id}), Songs: $songCount, Status: $linkedStatus"
            val priority = if (isLinked) android.util.Log.INFO else android.util.Log.ERROR
            android.util.Log.println(priority, "ArtistLinkDebug", message)


        }
        Log.i("ArtistLinkDebug", "--- END OF REPORT ---")
    }


    /**
     * Remove inaccessible, and duplicate songs from the library
     */
    suspend fun finalize(database: MusicDatabase, songsToFinalize: List<SongTempData>) {
        Log.i(TAG, "Start finalize (database cleanup job)")
        database.withSuspendingTransaction {
            val orphanedSongs = database.getOrphanedSongs()
            if (orphanedSongs.isNotEmpty()) {
                Log.i(TAG, "Start finalize (duplicate removal) job. Number of candidates: ${orphanedSongs.size}")
                orphanedSongs.forEach {
                    Log.i(TAG, "Deleting song ${it.id} (${it.title})")
                    database.delete(it)
                }
            }
        }

        Log.i("FolderScan", "finalize: Getting all artists for de-duplication.")
        val dbArtists = songsToFinalize.flatMap { it.song.artists }
            .distinctBy { it.id }
            .map { Artist(artist = it, songCount = 0, downloadCount = 0) }
            .toMutableList()

        Log.i(TAG, "Starting artist de-duplication. Found ${dbArtists.size} total artists to process.")

        val artistsByName = dbArtists.groupBy { it.artist.name.lowercase() }

        artistsByName.forEach { (artistName, artistGroup) ->
            if (artistGroup.size <= 1) return@forEach

            if (artistName.equals("matchbox 20", ignoreCase = true)) {
                Log.i("ArtistLinkDebug", "DE-DUPLICATION: Found group for 'matchbox 20' with ${artistGroup.size} entries.")
            }

            val masterArtist = artistGroup.first()
            val duplicates = artistGroup.drop(1)

            Log.d("ArtistLinkDebug", "Master is '${masterArtist.artist.name}' (${masterArtist.artist.id}). Merging ${duplicates.size} duplicates.")

            // Sequential merge to avoid type inference issues and ensure DB consistency
            for (duplicate in duplicates) {
                LocalMediaScanner.swapArtists(duplicate.artist, masterArtist.artist, database)
            }
        }

        Log.i(TAG, "Finished finalize (duplicate removal) job")
    }

    /**
     * Converts all local artists to remote artists if possible using the preferred linking strategy.
     * Includes enforcement for canonical albums ("Marion rule"), benefits of doubt for single songs,
     * relaxed subscriber count rules for unique matches, and deep parallelized inverse searches.
     */
    suspend fun localToRemoteArtist(database: MusicDatabase) {
        val prevScannerState = scannerState.value
        scannerState.value = 5
        val allLocal = database.allLocalArtists()
        scannerProgressTotal.value = allLocal.size

        // 1. Read user preference ONCE before the loop for efficiency and consistency
        val sensitivityName = context.dataStore[ArtistLinkingSensitivityKey] ?: ArtistLinkingSensitivity.COMPLEX.name
        val sensitivity = try { ArtistLinkingSensitivity.valueOf(sensitivityName) } catch (e: Exception) { ArtistLinkingSensitivity.COMPLEX }

        Log.i(TAG, "------------ SYNC: Starting youtube artist lookup ($sensitivity). Found ${allLocal.size} artists. ------------")

        scannerProgressCurrent.value = 0
        scannerProgressProbe.value = 0
        val mod = if (allLocal.size < 20) 2 else 8

        // Limit concurrency to 3 artists at a time to avoid being blocked by YouTube
        val semaphore = Semaphore(3)

        // List of artists allowed to print detailed linking logs
        val debugArtists = setOf(
            "ac/dc",
            "amps",
            "amy macdonald",
            "berenice",
            "catherine",
            "dolly",
            "double",
            "emma marrone",
            "emilie simon",
            "Émilie Simon",
            "george thorogood",
            "grease",
            "halo",
            "hooverphonic",
            "hot butter",
            "hot chocolate",
            "jean michel jarre",
            "joyrider",
            "killer",
            "lennon murphy",
            "marion",
            "melatonine",
            "muff",
            "nature Trip",
            "new order",
            "noa",
            "nuno",
            "pooka",
            "skid row",
            "syzygy",
            "the do",
            "the gathering",
            "total",
            "venus",
            "we insist",

            )

        // Regex for non-canonical albums: compilations, hits, years, folder fallbacks (§), or generic terms
        val invalidAlbumRegex = Regex("best|collection|anthology|essential|ultimate|greatest|hits|§|single|singles|^\\d+$", RegexOption.IGNORE_CASE)

        // Process artists in parallel using Deferred jobs
        val jobs: List<Deferred<Unit>> = allLocal.map { element ->
            CoroutineScope(lmScannerCoroutine).async {
                semaphore.withPermit {
                    val artistName = element.name.trim()

                    // Clean name for debug list check (handles non-breaking spaces and casing)
                    val cleanNameForDebug = artistName.lowercase().replace("\u00A0", " ").trim()
                    val shouldLog = debugArtists.contains(cleanNameForDebug)

                    val isLinkedOnYouTube = !element.isLocal || !element.channelId.isNullOrEmpty()

                    if (!isLinkedOnYouTube) {
                        try {
                            val artistName = element.name.trim()
                            val normalizedLocalName = artistName.normalizeForMatching()

                            // --- NEW LOGIC: Check for existing Liked/Bookmarked artist first ---
                            // We look for a non-local artist with the same name that is already liked.
                            val existingLikedArtist = withContext(Dispatchers.IO) {
                                database.allArtistsRaw().firstOrNull {
                                    it.name.normalizeForMatching() == normalizedLocalName &&
                                            !it.isLocal &&
                                            it.bookmarkedAt != null
                                }
                            }

                            if (existingLikedArtist != null) {
                                Log.i("ArtistLink", "[$artistName] Match found in Liked Artists (Normalized)! Linking to ${existingLikedArtist.id}")
                                swapArtists(element, existingLikedArtist, database)

                                // Update UI progress and move to next artist
                                withContext(Dispatchers.Main) {
                                    scannerProgressProbe.value++
                                    if (scannerProgressProbe.value % mod == 0) scannerProgressCurrent.value = scannerProgressProbe.value
                                }
                                return@withPermit
                            }
                            // --- END NEW LOGIC ---

                            // Proceed with YouTube search if no liked artist match was found
                            val searchResult = YouTube.search(artistName, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                            val normalizedArtistQuery = artistName.normalizeForMatching()

                            val allCandidates = searchResult?.items?.filterIsInstance<ArtistItem>()?.filter {
                                it.title.normalizeForMatching() == normalizedArtistQuery
                            } ?: emptyList()

                            if (allCandidates.isNotEmpty()) {
                                // PRE-FILTER: Identify which candidates are actually viable based on subscribers
                                // Rule: If at least one candidate has >= 100 subscribers, remove all those who don't.
                                val hasFamousCandidate = allCandidates.any { parseSubscribers(it.subscribers) >= 100 }
                                val viableCandidates = if (hasFamousCandidate) {
                                    allCandidates.filter { parseSubscribers(it.subscribers) >= 100 }
                                } else {
                                    allCandidates
                                }

                                val isUniqueValidMatch = viableCandidates.size == 1

                                var bestMatch: ArtistItem? = null
                                val validMatches = mutableListOf<Triple<ArtistItem, Long, String>>()

                                if (sensitivity == ArtistLinkingSensitivity.SIMPLE) {
                                    // --- SIMPLE LINKING: Pick most popular among name matches ---
                                    bestMatch = viableCandidates.maxByOrNull { parseSubscribers(it.subscribers) }
                                    Log.i("ArtistLink", "[$artistName] Simple Linking: Selected '${bestMatch?.title}' (${bestMatch?.id}) based on popularity.")
                                } else {
                                    // --- COMPLEX LINKING: Refined verification with song/album checks ---
                                    Log.i("ArtistLink", "------------------------------ ANALYSING LOCAL ARTIST: '$artistName' -------------------------------")

                                    val localSongs = database.artistSongsPreview(element.id, 15).first()
                                    val localAlbumTitles = localSongs.mapNotNull { it.song.albumName }
                                        .filter { it.isNotBlank() }
                                        .distinct()

                                    val validCanonicalLocalAlbums = localAlbumTitles.filter { !invalidAlbumRegex.containsMatchIn(it) }
                                    val hasCanonicalAlbumsLocally = validCanonicalLocalAlbums.isNotEmpty()
                                    val isSingleSongArtist = localSongs.size == 1

                                    if (shouldLog) Log.i("ArtistLink", "    [$artistName] [LOCAL CONTEXT] Songs: ${localSongs.map { it.song.title }}")
                                    if (shouldLog) Log.i("ArtistLink", "    [$artistName] [LOCAL CONTEXT] Albums: $localAlbumTitles (Canonical Count: ${validCanonicalLocalAlbums.size})")

                                    // Only process the candidates that passed our initial viable filter
                                    for (candidate in viableCandidates) {
                                        val subsString = candidate.subscribers ?: "0 subscribers"
                                        val subCount = parseSubscribers(subsString)

                                        if (shouldLog) Log.i("ArtistLink", "    [$artistName] > CHECKING CANDIDATE: '${candidate.title}' (Audience: $subsString, ID: ${candidate.id})")

                                        val ytArtistPage = YouTube.artist(candidate.id).getOrNull() ?: continue
                                        val ytItems = ytArtistPage.sections.flatMap { it.items }

                                        // 2. Prepare Online Context
                                        val ytSongTitlesNorm = ytItems.filterIsInstance<SongItem>().map { it.title.cleanTitle() }.toSet()
                                        val ytAlbumTitlesNorm = ytItems.filterIsInstance<AlbumItem>().map { it.title.cleanTitle() }.toSet()

                                        if (shouldLog) Log.i("ArtistLink", "    [$artistName]   [YT PAGE DATA] Songs: ${ytSongTitlesNorm.toList()}")
                                        if (shouldLog) Log.i("ArtistLink", "    [$artistName]   [YT PAGE DATA] Albums: ${ytAlbumTitlesNorm.toList()}")

                                        // 3. Compare Items (Standard Page Check)
                                        val matchedLocalSongs = mutableSetOf<String>()
                                        localSongs.forEach { localSong ->
                                            val localClean = localSong.song.title.cleanTitle()
                                            // Rule: Online song title contains local song title
                                            if (ytSongTitlesNorm.any { onlineClean -> onlineClean.contains(localClean) }) {
                                                if (shouldLog) Log.i("ArtistLink", "    [$artistName]   - SONG MATCH (Page): -> [FOUND] '${localSong.song.title}'")
                                                matchedLocalSongs.add(localSong.song.title)
                                            } else {
                                                if (shouldLog) Log.e("ArtistLink", "    [$artistName]   - SONG MATCH (Page): -> [NOT FOUND] '${localSong.song.title}'")
                                            }
                                        }

                                        val matchedLocalAlbums = mutableSetOf<String>()
                                        localAlbumTitles.forEach { localAlbum ->
                                            val localClean = localAlbum.cleanTitle()
                                            // Rule 1: Local album string contains online album string
                                            val isAlbumMatch = ytAlbumTitlesNorm.any { onlineClean -> localClean.contains(onlineClean) }
                                            // Rule 2: Online song title contains local album string (case where album tag = song title)
                                            val isSongAsAlbumMatch = ytSongTitlesNorm.any { onlineClean -> onlineClean.contains(localClean) }

                                            if (isAlbumMatch || isSongAsAlbumMatch) {
                                                if (shouldLog) Log.i("ArtistLink", "    [$artistName]   - ALBUM MATCH (Page): -> [FOUND] '$localAlbum'")
                                                matchedLocalAlbums.add(localAlbum)
                                            } else {
                                                if (shouldLog) Log.e("ArtistLink", "    [$artistName]   - ALBUM MATCH (Page): -> [NOT FOUND] '$localAlbum'")
                                            }
                                        }

                                        // 4. Inverse Search (Trigger if current evidence is insufficient or rule is failing)
                                        if (!(matchedLocalAlbums.isNotEmpty() && matchedLocalSongs.size >= 1) && matchedLocalSongs.size < 2) {
                                            if (shouldLog) Log.w("ArtistLink", "    [$artistName]   ? Matches insufficient. Searching YouTube direct for matches...")

                                            // Parallel search for songs
                                            val songSearchJobs = localSongs.take(5).filter { it.song.title !in matchedLocalSongs }.map { localSong ->
                                                async {
                                                    val res = YouTube.search("$artistName ${localSong.song.title}", YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                                    val isMatch = res?.items?.filterIsInstance<SongItem>()?.any { it.artists.any { a -> a.id == candidate.id } } == true
                                                    if (isMatch) {
                                                        if (shouldLog) Log.i("ArtistLink", "    [$artistName]   - SONG MATCH (Search): -> [FOUND via Search] '${localSong.song.title}'")
                                                        localSong.song.title
                                                    } else {
                                                        if (shouldLog) Log.e("ArtistLink", "    [$artistName]   - SONG MATCH (Search): -> [NOT FOUND via Search] '${localSong.song.title}'")
                                                        null
                                                    }
                                                }
                                            }

                                            // Parallel search for albums
                                            val albumSearchJobs = if (matchedLocalAlbums.isEmpty()) {
                                                localAlbumTitles.take(2).map { localAlbum ->
                                                    async {
                                                        val query = "$artistName $localAlbum"
                                                        // Search as Album
                                                        val albMatch = YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                                                            ?.items?.filterIsInstance<AlbumItem>()?.any { it.artists?.any { a -> a.id == candidate.id } == true }
                                                        if (albMatch == true) {
                                                            if (shouldLog) Log.i("ArtistLink", "    [$artistName]   - ALBUM MATCH (Search ALBUM): -> [FOUND via Search] '$localAlbum'")
                                                            return@async localAlbum
                                                        }
                                                        // Search as Song
                                                        val songMatch = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                                                            ?.items?.filterIsInstance<SongItem>()?.any { it.artists.any { a -> a.id == candidate.id } }
                                                        if (songMatch == true) {
                                                            if (shouldLog) Log.i("ArtistLink", "    [$artistName]   - ALBUM MATCH (Search SONG): -> [FOUND via Search] '$localAlbum'")
                                                            localAlbum
                                                        } else {
                                                            if (shouldLog) Log.e("ArtistLink", "    [$artistName]   - ALBUM MATCH (Search): -> [NOT FOUND via Search] '$localAlbum'")
                                                            null
                                                        }
                                                    }
                                                }
                                            } else emptyList()

                                            songSearchJobs.awaitAll().filterNotNull().forEach { matchedLocalSongs.add(it) }
                                            albumSearchJobs.awaitAll().filterNotNull().forEach { matchedLocalAlbums.add(it) }
                                        }

                                        val finalSongMatchCount = matchedLocalSongs.size
                                        val finalHasAlbumMatch = matchedLocalAlbums.isNotEmpty()

                                        // Apply rules
                                        val passesAlbumEnforcement = isUniqueValidMatch || isSingleSongArtist || !hasCanonicalAlbumsLocally || finalHasAlbumMatch

                                        Log.i("ArtistLink", "    [$artistName]   [SUMMARY] Candidate '${candidate.title}': Songs=$finalSongMatchCount, AlbumMatch=$finalHasAlbumMatch, AlbumEnforced=$hasCanonicalAlbumsLocally, UniqueCandidate=$isUniqueValidMatch")

                                        val isStandardMatch = (finalHasAlbumMatch && finalSongMatchCount >= 1) || finalSongMatchCount >= 2
                                        val isSingleSongMatch = isSingleSongArtist && finalSongMatchCount == 1
                                        val isHighConfidenceSongMatch = finalSongMatchCount >= 4

                                        if (isHighConfidenceSongMatch || (passesAlbumEnforcement && (isStandardMatch || isSingleSongMatch))) {
                                            validMatches.add(Triple(candidate, subCount, subsString))
                                            if (shouldLog) {
                                                val reason = if (isHighConfidenceSongMatch) "High confidence song match" else "Standard criteria"
                                                if (shouldLog) Log.i("ArtistLink", "    [$artistName]   >>> Candidate VALIDATED ($reason).")
                                            }
                                            break  // stop evaluating other candidates once a strong match is found
                                        } else {
                                            val reason = if (!passesAlbumEnforcement) "Failed album enforcement" else "Insufficient matches"
                                            if (shouldLog) Log.e("ArtistLink", "    [$artistName]   >>> Candidate REJECTED ($reason).")
                                        }
                                    }

                                    bestMatch = validMatches.firstOrNull()?.first
                                }

                                if (bestMatch != null) {
                                    Log.w("ArtistLink", "[$artistName] [SUCCESS] Linked ! .")
                                    val existingYtArtist = database.artist(bestMatch.id).firstOrNull()
                                    val finalYtArtist = existingYtArtist?.artist ?: ArtistEntity(id = bestMatch.id, name = bestMatch.title, thumbnailUrl = bestMatch.thumbnail, channelId = bestMatch.channelId)
                                    if (existingYtArtist == null) database.insert(finalYtArtist)
                                    swapArtists(element, finalYtArtist, database)
                                } else {
                                    Log.e("ArtistLink", "[$artistName] [RESULT] No winner for '$artistName'.")
                                }
                            } else {
                                Log.e("ArtistLink", "[$artistName] [RESULT] No name-matched candidates found on YouTube for '$artistName'.")
                            }
                        } catch (e: Exception) { reportException(e) }
                    }

                    // Update UI progress
                    withContext(Dispatchers.Main) {
                        scannerProgressProbe.value++
                        if (scannerProgressProbe.value % mod == 0) {
                            scannerProgressCurrent.value = scannerProgressProbe.value
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        scannerState.value = prevScannerState
        Log.i(TAG, "------------ SYNC: youtubeArtistLookup ended ------------")
    }

    private fun parseSubscribers(subs: String?): Long {
        if (subs == null) return 0L
        val cleaned = subs.lowercase()
        val multiplier = when {
            cleaned.contains("m") -> 1_000_000.0
            cleaned.contains("k") -> 1_000.0
            else -> 1.0
        }
        return ((cleaned.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0) * multiplier).toLong()
    }



// **************************************************************************************** //

    companion object {
        /**
         * Builds a DirectoryTree from a provided list of songs.
         * This is used to reconstruct the folder view without a full file scan.
         */
        fun buildDirectoryTree(songs: List<Song>): DirectoryTree {
            val root = DirectoryTree(STORAGE_ROOT, CulmSongs(0))

            var logCounter = 0
            songs.forEach { song ->
                song.song.localPath?.let { path ->
                    // --- DEFINITIVE FIX: Calculate the path relative to the root ---
                    val relativePath = if (path.startsWith(STORAGE_ROOT)) {
                        path.substringAfter(STORAGE_ROOT).trimStart('/')
                    } else {
                        // Fallback for paths that don't conform to the standard root
                        path
                    }
                    if (logCounter % 20 == 0) {
                        Log.d("FolderScan", "buildDirectoryTree: Inserting path '$relativePath' for song '${song.song.title}'")
                    }
                    logCounter++

                    root.insert(relativePath, song)
                    // --- END FIX ---
                }
            }
            root.isSkeleton = false
            return root
        }

        // do not put any thing that should adhere to the scanner lock in here
        const val TAG = "LocalMediaScanner"

        private var ownerId = -1
        private var localScanner: LocalMediaScanner? = null


        var scannerRequestCancel = false

        /**
         * -1: Inactive
         * 0: Idle
         * 1: Discovering (Crawling files)
         * 2: Scanning (Extract metadata and checking playability
         * 3: Syncing (Update database)
         * 4: Scan finished
         * 5: Ytm artist linking
         */
        var scannerState = MutableStateFlow(-1)
        var scannerProgressTotal = MutableStateFlow(-1)
        var scannerProgressCurrent = MutableStateFlow(-1)
        var scannerProgressProbe = MutableStateFlow(-1)


        /**
         * ==========================
         * Scanner management
         * ==========================
         */

        /**
         * Trust me bro, it should never be null
         */
        fun getScanner(context: Context, scannerImpl: ScannerImpl, owner: Int): LocalMediaScanner {

            if (localScanner == null) {
                // reset to taglib if ffMetadataEx disappears
                if (scannerImpl == ScannerImpl.FFMPEG_EXT && !ENABLE_FFMETADATAEX) {
                    CoroutineScope(lmScannerCoroutine).launch {
                        context.dataStore.edit { settings ->
                            settings[ScannerImplKey] = ScannerImpl.TAGLIB.toString()
                            settings[AutomaticScannerKey] = false
                            runBlocking(Dispatchers.Main) {
                                // TODO: string resource (but will anyone even notice this...)
                                Toast.makeText(context, "FFmpeg extractors are missing", Toast.LENGTH_SHORT).show()
                                Toast.makeText(
                                    context,
                                    "Auto scanner has been disabled to prevent data conflicts. You will need to enable this in local media settings again if you want automatic scanning.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
                localScanner = try {
                    LocalMediaScanner(context, scannerImpl)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "FFmpeg library missing! Falling back to TagLib.", e)
                    // If FFmpeg fails to load, force TagLib instead
                    LocalMediaScanner(context, com.dd3boh.outertune.constants.ScannerImpl.TAGLIB)
                }
                scannerProgressTotal.value = 0
                scannerProgressCurrent.value = -1
                scannerProgressProbe.value = 0
            }

            ownerId = owner
            return localScanner!!
        }

        fun destroyScanner(owner: Int) {
            if (owner != ownerId && ownerId != -1) {
                Log.w(TAG, "Scanner instance can only be destroyed by the owner. Aborting. Check your ownerId.")
                return
            }
            ownerId = -1
            localScanner = null
            scannerState.value = -1
            scannerRequestCancel = false
            scannerProgressTotal.value = -1
            scannerProgressCurrent.value = -1
            scannerProgressProbe.value = -1

            Log.i(TAG, "Scanner instance destroyed")
        }


        /**
         * ==========================
         * Scanner extra scan utils
         * ==========================
         */


        /**
         * Build a list of files to scan, taking in exclusions into account. Exclusions
         * will override inclusions. All subdirectories will also be affected.
         *
         * Uri.path can be assumed to be non-null
         */
        fun getScanFiles(scanPaths: List<Uri>, excludedScanPaths: List<Uri>, context: Context): List<Uri> {
            val allSongs = ArrayList<Uri>()
            val resultingPaths =
                scanPaths.filterNot { incl ->
                    excludedScanPaths.any { excl -> incl.path?.startsWith(excl.path.toString()) == true }
                }

            resultingPaths.forEach { path ->
                try {
                    val file = documentFileFromUri(context, path)
                    if (file != null) {
                        val songsHere = ArrayList<DocumentFile>()
                        scanDfRecursive(file, songsHere) {
                            // Allow: audio mime, or certain audio exts
                            // Disallow: x-mpegurl (m3u)
                            val mime = it.type ?: return@scanDfRecursive false
                            if (!mime.startsWith("audio")) {
                                if (it.name?.substringAfterLast('.') !in scannerWhitelistExts) {
                                    return@scanDfRecursive false
                                }
                            }
                            if (mime == "audio/x-mpegurl") {
                                return@scanDfRecursive false
                            }

                            return@scanDfRecursive true
                        }

                        allSongs.addAll(songsHere.fastFilter { incl ->
                            !excludedScanPaths.any {
                                incl.uri.path?.startsWith(it.path.toString()) == true
                            }
                        }.map { it.uri })
                    }
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                    throw Exception("oh well idk man this should never happen")
                }
            }

            return allSongs.fastDistinctBy { it.toString() }
        }

        fun scanDfRecursive(
            dir: DocumentFile,
            result: ArrayList<DocumentFile>,
            scanHidden: Boolean = false,
            validator: ((DocumentFile) -> Boolean)? = null
        ): DocumentFile? {
            val files = dir.listFiles()
            for (file in files) {
                if (!scanHidden && file.name?.startsWith(".") == true) continue
                if (file.isDirectory && (scanHidden || !file.listFiles().any { it.name == ".nomedia" })) {
                    // look into subdirs
                    scanDfRecursive(file, result, scanHidden, validator)
                } else {
                    // add if file matches
                    if (validator == null || validator(file)) {
                        result.add(file)
                        scannerProgressProbe.value++
                        if (scannerProgressProbe.value % 20 == 0) {
                            scannerProgressTotal.value = scannerProgressProbe.value
                        }
                    }
                }
            }
            return null
        }

        /**
         * Quickly rebuild a skeleton directory tree of local files based on the database
         */
        suspend fun refreshLocal(
            database: MusicDatabase,
            filter: String
        ): DirectoryTree {
            val newDirectoryStructure = DirectoryTree(filter.trimEnd { it == '/' }, CulmSongs(0))
            val existingSongs: List<Song> = database.localSongsInDirShallow(filter)
            Log.i(TAG, "------------ SCAN: Starting Quick Directory Rebuild ------------")

            existingSongs.forEach { s ->
                val path = s.song.localPath ?: return@forEach
                // --- DEFINITIVE FIX: Calculate the path relative to the filter ---
                val relativePath = if (path.startsWith(filter)) {
                    path.substringAfter(filter).trimStart('/')
                } else {
                    path
                }
                newDirectoryStructure.insert(relativePath, s)
                // --- END FIX ---
            }

            Log.i(TAG, "------------ SCAN: Finished Quick Directory Rebuild ------------")
            return newDirectoryStructure.androidStorageWorkaround()
        }


        /**
         * ==========================
         * Scanner helpers
         * ==========================
         */


        /**
         * Check if artists are the same
         */
        fun compareArtist(a: List<ArtistEntity>, b: List<ArtistEntity>): Boolean {
            if (a.isEmpty() && b.isEmpty()) {
                return true
            } else if (a.isEmpty() || b.isEmpty()) {
                return false
            }

            if (a.size != b.size) {
                return false
            }
            val matchingArtists = a.filter { artist ->
                b.any { it.name.equals(artist.name, false) }
            }

            return matchingArtists.size == a.size
        }

        /**
         * Check if albums are the same
         */
        fun compareAlbum(a: AlbumEntity?, b: AlbumEntity?): Boolean {
            if (a == null && b == null) return true
            if (a == null || b == null) return false

            return a.title.equals(b.title, false)
        }

        /**
         * Check the similarity of a song
         */
        fun compareM3uSong(
            a: Song,
            b: Song,
            matchStrength: ScannerM3uMatchCriteria = ScannerM3uMatchCriteria.LEVEL_1,
        ): Boolean {
            val matchStrength = when (matchStrength) {
                ScannerM3uMatchCriteria.LEVEL_1 -> ScannerMatchCriteria.LEVEL_1
                ScannerM3uMatchCriteria.LEVEL_2 -> ScannerMatchCriteria.LEVEL_2
                else -> ScannerMatchCriteria.LEVEL_1
            }
            return compareSong(a, b, matchStrength)
        }

        /**
         * Check the similarity of a song
         */
        fun compareSong(
            a: Song,
            b: Song,
            matchStrength: ScannerMatchCriteria = ScannerMatchCriteria.LEVEL_2,
            strictFileNames: Boolean = false,
            strictFilePaths: Boolean = false,
        ): Boolean {
            /**
             * Compare file paths
             */
            fun closeEnough(): Boolean {
                return a.song.localPath == b.song.localPath
            }
            if (strictFilePaths) {
                return closeEnough()
            }
            // if match file names
            if (strictFileNames &&
                (a.song.localPath?.substringAfterLast('/') !=
                        b.song.localPath?.substringAfterLast('/'))
            ) {
                return false
            }

            // compare songs based on scanner strength
            return when (matchStrength) {
                ScannerMatchCriteria.LEVEL_1 -> a.song.title == b.song.title
                ScannerMatchCriteria.LEVEL_2 -> closeEnough() || (a.song.title == b.song.title &&
                        compareArtist(a.artists, b.artists))

                ScannerMatchCriteria.LEVEL_3 -> closeEnough() || (a.song.title == b.song.title &&
                        compareArtist(a.artists, b.artists) && compareAlbum(a.album, b.album))
            }
        }

        /**
         * Search for an artist on YouTube Music.
         */
        suspend fun youtubeSongLookup(query: String, songUrl: String?): List<MediaMetadata> {
            val ytmResult = ArrayList<MediaMetadata>()

            var exactSong: SongItem? = null
            if (songUrl != null) {
                runCatching {
                    YouTube.queue(listOf(songUrl.substringAfter("/watch?v=").substringBefore("&")))
                }.onSuccess {
                    exactSong = it.getOrNull()?.firstOrNull()
                }.onFailure {
                    reportException(it)
                }
            }

            // prefer song from url
            if (exactSong != null) {
                ytmResult.add(exactSong.toMediaMetadata())
                if (SCANNER_DEBUG)
                    Log.v(TAG, "Found exact song: ${exactSong.title} [${exactSong.id}]")
                return ytmResult
            }
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).onSuccess { result ->

                val foundSong = result.items.filter {
                    // TODO: might want to implement proper matching to remove outlandish results
                    it is SongItem
                }
                ytmResult.addAll(foundSong.map { (it as SongItem).toMediaMetadata() })

                if (SCANNER_DEBUG)
                    Log.v(TAG, "Remote song: ${foundSong.firstOrNull()?.title} [${foundSong.firstOrNull()?.id}]")
            }.onFailure {
                throw Exception("Failed to search on YouTube Music: ${it.message}")
            }

            return ytmResult
        }

        suspend fun swapArtists(old: ArtistEntity, new: ArtistEntity, database: MusicDatabase) {
            if (old.id == new.id) return

            Log.d(TAG, "Swapping songs from old artist ${old.name} (${old.id}) to master artist ${new.name} (${new.id})")

            val songMapsToSwap = database.getSongArtistMapsByArtist(old.id)
            if (songMapsToSwap.isEmpty()) {
                database.safeDeleteArtist(old.id)
                return
            }

            database.delete(songMapsToSwap)
            val newMaps = songMapsToSwap.map { it.copy(artistId = new.id) }
            database.insert(newMaps)
            database.safeDeleteArtist(old.id)
        }

        fun swapAlbums(old: AlbumEntity, new: AlbumEntity, database: MusicDatabase) {
            database.transaction {
                if (albumById(old.id) == null) {
                    reportException(Exception("Attempting to swap with non-existent old album in database with id: ${old.id}"))
                    return@transaction
                }
                if (albumById(new.id) == null) {
                    reportException(Exception("Attempting to swap with non-existent new album in database with id: ${new.id}"))
                    return@transaction
                }

                // update participation(s)
                updateSongAlbumMap(old.id, new.id)

                // nuke old artist
                safeDeleteAlbum(old.id)
            }
        }

        fun swapGenres(old: GenreEntity, new: GenreEntity, database: MusicDatabase) {
            database.transaction {
                if (genreById(old.id) == null) {
                    reportException(Exception("Attempting to swap with non-existent old album in database with id: ${old.id}"))
                    return@transaction
                }
                if (genreById(new.id) == null) {
                    reportException(Exception("Attempting to swap with non-existent new album in database with id: ${new.id}"))
                    return@transaction
                }

                // update participation(s)
                updateSongGenreMap(old.id, new.id)

                // nuke old genre
                safeDeleteGenre(old.id)
            }
        }
    }
}

class InvalidAudioFileException(message: String) : Throwable(message)
class ScannerAbortException(message: String) : Throwable(message)
class ScannerCriticalFailureException(message: String) : Throwable(message)
