/*
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.models

import android.util.Log
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastSumBy
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.constants.FolderSortType
import com.dd3boh.outertune.constants.SCANNER_DEBUG
import com.dd3boh.outertune.constants.SongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.ui.utils.uninitializedDirectoryTree
import com.dd3boh.outertune.utils.fixFilePath
import com.dd3boh.outertune.utils.numberToAlpha
import java.time.ZoneOffset

/**
 * A tree representation of local audio files
 *
 * @param path root directory start
 */
class DirectoryTree(path: String, var culmSongs: CulmSongs) {
    companion object {
        const val TAG = "DirectoryTree"
        var directoryUID = 0
    }

    /**
     * Directory name
     */
    var currentDir = path // file name

    /**
     * Full parent directory path
     */
    var parent: String = ""

    // folder contents
    var subdirs = ArrayList<DirectoryTree>()
    var files = ArrayList<Song>()

    val uid = directoryUID

    var isSkeleton = true

    init {
        // increment uid
        directoryUID++
    }

    /**
     * Instantiate a directory tree directly with songs
     */
    constructor(path: String, culmSongs: CulmSongs, files: ArrayList<Song>) : this(path, culmSongs) {
        this.files = files
    }

    /**
     * Instantiate a directory tree directly with subdirectories and songs
     */
    constructor(
        path: String,
        culmSongs: CulmSongs,
        subdirs: ArrayList<DirectoryTree>,
        files: ArrayList<Song>
    ) : this(path, culmSongs) {
        this.subdirs = subdirs
        this.files = files
    }

    fun insert(path: String, song: Song) {
        // --- DEFINITIVE FIX: A robust, recursive path parser ---
        // Trim leading slashes to handle paths like "/Music/Artist/Album/song.mp3"
        //Log.i("FolderScan", "DirectoryTree.insert: Node '${this.currentDir}' received path: '$path'")
        val cleanPath = path.trimStart('/')

        // Find the first directory separator.
        val separatorIndex = cleanPath.indexOf('/')

        // If there are no more separators, we are at the file level. Add the song.
        if (separatorIndex == -1) {
            // The last part of the path is the filename. We don't create a folder for it.
            // We simply add the song to the current directory node.
            this.files.add(song)
            this.culmSongs.value++
            return
        }

        // If there is a separator, get the current directory segment and the rest of the path.
        val currentSegment = cleanPath.substring(0, separatorIndex)
        val restOfPath = cleanPath.substring(separatorIndex + 1)

        // Find or create the subdirectory for the current segment.
        var subdir = subdirs.fastFirstOrNull { it.currentDir == currentSegment }
        if (subdir == null) {
            subdir = DirectoryTree(currentSegment, this.culmSongs)
            subdir.parent = this.getFullPath()
            this.subdirs.add(subdir)
        }

        // Recurse into the subdirectory with the rest of the path.
        subdir.insert(restOfPath, song)
        // --- END FIX ---
    }

    /**
     * Get the name of the file from full path, without any extensions
     */
    private fun getFileName(path: String?): String? {
        if (path == null) {
            return null
        }
        return path.substringAfterLast('/').substringBefore('.')
    }

    /**
     * Retrieves song object at path
     *
     * @return song at path, or null if it does not exist
     */
    fun getSong(path: String): Song? {
        Log.v(TAG, "Searching for song, at path: $path")

        // search for song in current dir
        if (path.indexOf('/') == -1) {
            val foundSong: Song = files.first { getFileName(it.song.localPath) == getFileName(path) }
            Log.v(TAG, "Searching for song, found?: ${foundSong.id} Name: ${foundSong.song.title}")
            return foundSong
        }

        // there is still subdirs to process
        var tmpPath = path
        if (path[path.length - 1] == '/') {
            tmpPath = path.substring(0, path.length - 1)
        }

        // the first directory before the .
        val subdirPath = tmpPath.substringBefore('/')

        // scan for matching subdirectory
        var existingSubdir: DirectoryTree? = null
        subdirs.forEach { subdir ->
            if (subdir.currentDir == subdirPath) {
                existingSubdir = subdir
                return@forEach
            }
        }

        // explore the subdirectory if it exists in
        return existingSubdir?.getSong(tmpPath.substringAfter('/'))
    }

    fun getTotalSongCount(): Int {
        // --- Recursively count all songs ---
        return files.size + subdirs.sumOf { it.getTotalSongCount() }
    }

    /**
     * Retrieve a list of all the songs
     */
    fun toList(): List<Song> {
        val songs = ArrayList<Song>()

        fun traverseTree(tree: DirectoryTree, result: ArrayList<Song>) {
            result.addAll(tree.files)
            tree.subdirs.forEach { traverseTree(it, result) }
        }

        traverseTree(this, songs)
        return songs
    }

    /**
     * Retrieve a list of all the songs in the current directory, adhering to sort preferences.
     */
    fun toSortedList(sortType: FolderSongSortType, sortDescending: Boolean): List<Song> {
        val songs = files.toMutableList()

        // sort songs. Ignore any subfolder structure
        songs.sortBy {
            when (sortType) {
                FolderSongSortType.CREATE_DATE -> numberToAlpha(it.song.inLibrary?.toEpochSecond(ZoneOffset.UTC) ?: -1L)
                FolderSongSortType.MODIFIED_DATE -> numberToAlpha(it.song.getDateModifiedLong() ?: -1L)
                FolderSongSortType.RELEASE_DATE -> numberToAlpha(it.song.getDateLong() ?: -1L)
                FolderSongSortType.NAME -> it.song.title.lowercase()
                FolderSongSortType.ARTIST -> it.artists.joinToString { artist -> artist.name }.lowercase()
                FolderSongSortType.PLAY_COUNT -> numberToAlpha((it.playCount?.fastSumBy { it.count })?.toLong() ?: 0L)
                FolderSongSortType.TRACK_NUMBER -> numberToAlpha(it.song.trackNumber?.toLong() ?: Long.MAX_VALUE)
            }
        }

        if (sortDescending) {
            songs.reverse()
        }
        return songs
    }

    /**
     * Retrieve a list of all the songs in the current directory including subdirectories, adhering to sort preferences.
     * Subfolder structure will be completely ignored.
     */
    fun toSortedListRecursive(sortType: SongSortType, sortDescending: Boolean): List<Song> {
        val songs = ArrayList<Song>()

        fun traverseTree(tree: DirectoryTree, result: ArrayList<Song>) {
            result.addAll(tree.files)
            tree.subdirs.forEach { traverseTree(it, result) }
        }

        traverseTree(this, songs)

        // sort songs. Ignore any subfolder structure
        songs.sortBy {
            when (sortType) {
                SongSortType.CREATE_DATE -> it.song.inLibrary?.toEpochSecond(ZoneOffset.UTC).toString()
                SongSortType.MODIFIED_DATE -> it.song.getDateModifiedLong().toString()
                SongSortType.RELEASE_DATE -> it.song.getDateLong().toString()
                SongSortType.NAME -> it.song.title
                SongSortType.ARTIST -> it.artists.firstOrNull()?.name
                SongSortType.PLAY_COUNT -> it.playCount.toString()
            }
        }

        if (sortDescending) {
            songs.reverse()
        }
        return songs
    }

    /**
     * Retrieves a modified version of this DirectoryTree.
     * All folders are recognized to be top level folders
     */
    fun getFlattenedSubdirs(
        includeEmpty: Boolean = false,
        sortType: FolderSortType,
        sortDescending: Boolean
        ): List<DirectoryTree> {
        val result = ArrayList<DirectoryTree>()
        getSubdirsRecursive(this, result, includeEmpty = includeEmpty)

        // --- DEFINITIVE FIX: Sort the list before returning it ---
        if (sortType == FolderSortType.NAME) {
            result.sortBy { it.currentDir.lowercase() }
        }
        // Future sort types for folders can be added here.

        if (sortDescending) {
            result.reverse()
        }
        // --- END FIX ---

        return result
    }


    fun getSubDir(path: String): DirectoryTree {
        // 1. Split path and remove "storage" and empty parts
        val segments = path.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("storage", ignoreCase = true) }

        if (segments.isEmpty()) return this

        var currentNode: DirectoryTree = this

        // 2. Iterate through path segments.
        for (segment in segments) {
            // Check if this segment matches the current node's name (skips redundant root segments)
            val cleanCurrent = currentNode.currentDir.removeSurrounding("/")
            if (segment.equals(cleanCurrent, ignoreCase = true)) {
                continue
            }

            // Look for a child that matches the segment
            val nextNode = currentNode.subdirs.fastFirstOrNull {
                it.currentDir.equals(segment, ignoreCase = true)
            }

            if (nextNode != null) {
                currentNode = nextNode
            } else {
                // Self-healing: if no match is found, we skip this segment and try the next one
                // This handles various "trimmed" tree root formats.
                Log.v("FolderScan", "getSubDir: segment '$segment' not found, skipping...")
            }
        }

        return currentNode
    }

    /**
     * Migrate emulated/0 path to "Internal" within the DirectoryTree.
     * This operation makes these edits directly to this object.
     * Calling this method on a tree that already has been migrated does nothing.
     *
     *
     * Why is this even necessary? Android internal volume is stored under "storage/emulated/0",
     * however for external volumes (like ext. sdcards), they are stored under "storage/<id>".
     * Flatten this to make the UI require less pointless clicks.
     *
     * @return This object, after migrating
     */
    fun androidStorageWorkaround(): DirectoryTree {
        if (currentDir == "/" && subdirs.size == 1 && files.isEmpty()) {
            return DirectoryTree("/storage", culmSongs, subdirs.first().subdirs, subdirs.first().files)
        }

        return this
    }

    /**
     * Remove any single empty branches of the tree, aka. DirectoryTrees with no files,
     * but only one subdirectory.
     */
    fun trimRoot(): DirectoryTree {
        var pointer = this
        while (pointer.subdirs.size == 1 && pointer.files.isEmpty()) {
            pointer = pointer.subdirs[0]
        }

        this.currentDir = pointer.currentDir
        this.files = pointer.files
        this.subdirs = pointer.subdirs

        return this
    }

    fun getSquashedDir(): String {
        // get full path of blank folders
        // subdir size is 1, and filesize is 0

        if (subdirs.size != 1 && files.size != 0) {
            return currentDir
        } else {
            var ret = ""
            var isEmpty = true
            fun exploreSubdirs(dt: DirectoryTree) {
                if (!isEmpty || dt.subdirs.size != 1 || dt.files.isNotEmpty()) {
                    if (isEmpty) {
                        ret += "/${dt.currentDir}"
                    }
                    isEmpty = false
                    return
                } else {
                    ret += "/${dt.currentDir}"
                    dt.subdirs.forEach {
                        exploreSubdirs(it)
                    }
                }
            }
            exploreSubdirs(this)

            var retdir = ret.trimStart() { it == '/' }.trimEnd { it == '/' }
            Log.i("FolderScan", "ret '${ret}' - returning '${retdir}'")
            return retdir
        }
    }

    fun getFullSquashedDir(): String {
        Log.i("FolderScan", "getFullSquashedDir called for '${this.currentDir}'")
        return getSquashedDir()
    }

    fun getFullPath(): String {
        Log.d("FolderScan", "getFullPath called for '${this.currentDir}' with parent '${this.parent}'")
        // --- DEFINITIVE FIX: Prevent duplicate 'storage' and double slashes ---
        val p = parent.removeSuffix("/")
        val c = currentDir.removePrefix("/")

        // Handle the root case where parent is empty and currentDir is "storage"
        if (p.isEmpty() && c == "storage") {
            return "/storage"
        }

        // Prevent "storage/storage"
        if (p == "/storage" && c.startsWith("storage")) {
            return "/$c"
        }

        return "$p/$c"
    }

    /**
     * Crawl the directory tree, add the subdirectories with songs to the list
     * @param it
     * @param result
     */
    private fun getSubdirsRecursive(
        it: DirectoryTree,
        result: ArrayList<DirectoryTree>,
        includeEmpty: Boolean = false
    ) {
        if (includeEmpty || it.files.isNotEmpty()) {
            result.add(it)
        }

        if (it.subdirs.isNotEmpty()) {
            it.subdirs.forEach { getSubdirsRecursive(it, result, includeEmpty) }
        }
    }
}

// TODO: delete dis if unused
data class CulmSongs(var value: Int)
