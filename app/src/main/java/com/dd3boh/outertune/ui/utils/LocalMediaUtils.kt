/*
 * Copyright (C) 2025 O⁠ute⁠rTu⁠ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.utils

import android.Manifest
import android.os.Build
import android.util.Log
import com.dd3boh.outertune.models.CulmSongs
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.utils.fixFilePath
import com.dd3boh.outertune.db.entities.Song

const val TAG = "LocalMediaUtils"
const val EXTRACTOR_TAG = "MetadataExtractor"

val MEDIA_PERMISSION_LEVEL =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
const val STORAGE_ROOT = "/storage/"
//val ARTIST_SEPARATORS = Regex("\\s*;\\s*|\\s*ft\\.\\s*|\\s*feat\\.\\s*|\\s*&\\s*|\\s*,\\s*", RegexOption.IGNORE_CASE)
val ARTIST_SEPARATORS = Regex("\\s*;\\s*|\\s*ft\\.\\s*|\\s*feat\\.\\s*", RegexOption.IGNORE_CASE) // I want the & and , to be considered as part of the band's name. Do not separate artists based on that.
val uninitializedDirectoryTree = DirectoryTree("uninitialized", CulmSongs(0))

// --- DEFINITIVE FIX: Use a single, master root for the cache ---
private var masterRootDirectoryTree: DirectoryTree = DirectoryTree(STORAGE_ROOT, CulmSongs(0))
// --- END FIX ---


/**
 * ==========================
 * Various misc helpers
 * ==========================
 */


/**
 * Get cached DirectoryTree
 */
fun getDirectoryTree(path: String): DirectoryTree {
    val fixedPath = fixFilePath(path)
    Log.i("FolderScan", "getDirectoryTree in LocalMediaUtils: UI is requesting fixed path: '$fixedPath'")
    // --- DEFINITIVE FIX: Recursively search the master tree ---
    return masterRootDirectoryTree.getSubDir(fixedPath)
    // --- END FIX ---
}

/**
 * Rebuilds the entire DirectoryTree cache from a fresh list of all local songs.
 */
fun rebuildDirectoryTreeCache(allLocalSongs: List<Song>) {
    Log.i("FolderScan", "rebuildDirectoryTreeCache: Rebuilding cache with ${allLocalSongs.size} songs.")
    clearDtCache() // Clear all old data

    // --- DEFINITIVE FIX: Rebuild the single master tree ---
    val newRoot = DirectoryTree(STORAGE_ROOT, CulmSongs(0))
    allLocalSongs.forEach { song ->
        song.song.localPath?.let { path ->
            val relativePath = if (path.startsWith(STORAGE_ROOT)) {
                path.substringAfter(STORAGE_ROOT)
            } else {
                path
            }
            newRoot.insert(relativePath, song)
        }
    }
    newRoot.isSkeleton = false
    masterRootDirectoryTree = newRoot.androidStorageWorkaround()
    // --- END FIX ---
}

fun clearDtCache() {
    masterRootDirectoryTree = DirectoryTree(STORAGE_ROOT, CulmSongs(0))
    DirectoryTree.directoryUID = 0
}
