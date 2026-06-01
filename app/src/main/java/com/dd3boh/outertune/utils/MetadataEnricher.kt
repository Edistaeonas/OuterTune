package com.dd3boh.outertune.utils

import android.util.Log
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import java.io.IOException

object MetadataEnricher {
    private const val TAG = "MetadataEnricher"

    // Keywords for albums that should be ignored when looking for the original release year.
    // We avoid Live albums, Best Of collections, etc., as they often have misleading dates.
    private val JUNK_ALBUM_REGEX = Regex("\\b(live|best|collection|anthology|essential|ultimate|greatest|hits|remastered|remaster)\\b", RegexOption.IGNORE_CASE)

    data class EnrichmentResult(
        val albumTitle: String?,
        val albumId: String?,
        val albumArtist: String?,
        val year: Int?,          // The earliest year found
        val standardYear: Int?,  // The year of the FIRST search result
        val thumbnailUrl: String?,
        val albumPage: AlbumPage?,
        val trackNumber: Int?
    )

    /**
     * Core logic to find better metadata for a song on YouTube.
     * Returns null if no match is found or if a network error occurs.
     */
    suspend fun findEnrichment(songTitle: String, artistName: String): EnrichmentResult? {
        Log.i(TAG, "ENRICH METADATA : Attempting metadata enrichment for '$songTitle' by '$artistName'...")

        // Step 1: Clean title for search
        // Removes "(Remastered)", "[Deluxe]", etc. to find the original release
        val searchTitle = songTitle.replace(Regex("\\s*[(\\[].*?\\b(remaster|edition|deluxe|anniversary|version|digital|bonus|expanded|best of|greatest hits|collection)\\b.*?[)\\]]", RegexOption.IGNORE_CASE), "").trim()

        val searchResult = YouTube.search(
            query = "$artistName - $searchTitle",
            filter = YouTube.SearchFilter.FILTER_SONG
        )

        if (searchResult.isFailure) {
            Log.w(TAG, "ENRICH METADATA : Search failed for '$songTitle'")
            return null
        }

        val items = searchResult.getOrNull()?.items ?: return null
        Log.i(TAG, "ENRICH METADATA : Found ${items.size} search results total.")

        // Step 2: Find best candidates
        // ---   Verify Artist match AND Title match ---
        // We check top 5 results and use a flexible title match (ignoring brackets)

        val candidates = items
            .filterIsInstance<SongItem>()
            .filter {
                // Remove brackets and leading/trailing junk for a clean comparison
                val cleanYt = it.title.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").trim()
                // Robust local cleaning:
                // 1. Remove prefix icons (¤, •)
                // 2. Remove leading track number [12] and any following hyphen/space
                // 3. Remove any trailing bracketed info like [9] or (Remaster)
                val cleanLocal = songTitle.replace(Regex("^[•¤\\s]+"), "")
                    .replace(Regex("^\\[\\d+\\][\\s-]*"), "")
                    .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
                    .trim()

                val isMatch = cleanYt.equals(cleanLocal, ignoreCase = true) &&
                        it.artists.any { a -> a.name.equals(artistName, ignoreCase = true) }

                if (isMatch) {
                    Log.d(TAG, "ENRICH METADATA : Candidate Match: '${it.title}' on album '${it.album?.name}'")
                }
                isMatch
            }
            .take(5)

        if (candidates.isEmpty()) {
            Log.w(TAG, "ENRICH METADATA : No matching candidates found for '$songTitle'.")
            return null
        }

        var absoluteEarliestYear: Int? = null
        var standardYear: Int? = null
        var bestAlbumPage: AlbumPage? = null
        var trackNumber: Int? = null

        // Pass 1: Analyze candidates and their versions to find the original release.
        for ((idx, candidate) in candidates.withIndex()) {
            val albumId = candidate.album?.id ?: continue
            val albumPage = YouTube.album(albumId).getOrNull() ?: continue

            Log.i(TAG, "ENRICH METADATA : Checking album candidate: '${albumPage.album.title}' (${albumPage.album.year})")

            if (idx == 0) {
                // Initialize baseline with the primary search result
                standardYear = albumPage.album.year
                bestAlbumPage = albumPage
                absoluteEarliestYear = standardYear

                val tIdx = bestAlbumPage!!.songs.indexOfFirst { it.id == candidate.id }.takeIf { it != -1 }
                    ?: bestAlbumPage!!.songs.indexOfFirst { it.title.equals(candidate.title, ignoreCase = true) }.takeIf { it != -1 }
                trackNumber = tIdx?.let { it + 1 }

                // --- REQUIREMENT: Stop if the primary choice is Live ---
                if (albumPage.album.title.contains("Live", ignoreCase = true)) {
                    Log.i(TAG, "ENRICH METADATA : Primary match is Live. Skipping studio year hunt.")
                    break
                }
            }

            // --- REQUIREMENT: Filter out Compilations/Live/Remasters when hunting original year ---
            val allVersions = listOf(albumPage.album) + albumPage.otherVersions

            for (v in allVersions) {
                val vYear = v.year ?: continue
                val vIsJunk = v.title.contains(JUNK_ALBUM_REGEX)
                val currentIsJunk = bestAlbumPage?.album?.title?.contains(JUNK_ALBUM_REGEX) ?: true

                // Logic:
                // 1. If we find a "Clean" album and current is "Junk", switch immediately.
                // 2. If both are the same type, take the earlier year.
                val shouldUpdate = if (!vIsJunk && currentIsJunk) {
                    true
                } else if (vIsJunk == currentIsJunk) {
                    vYear < (absoluteEarliestYear ?: Int.MAX_VALUE)
                } else {
                    false // Never switch from Clean back to Junk
                }

                if (shouldUpdate) {
                    Log.i(TAG, "ENRICH METADATA :   -> Found better release: $vYear (${v.title})")
                    absoluteEarliestYear = vYear

                    if (v.browseId != bestAlbumPage?.album?.browseId) {
                        YouTube.album(v.browseId).getOrNull()?.let { newPage ->
                            bestAlbumPage = newPage
                            val tIdx = bestAlbumPage!!.songs.indexOfFirst { it.title.equals(candidate.title, ignoreCase = true) }.takeIf { it != -1 }
                            trackNumber = tIdx?.let { it + 1 }
                        }
                    }
                }
            }
        }

        return bestAlbumPage?.let { page ->
            Log.i(TAG, "ENRICH METADATA : Final selection: '${page.album.title}' Year: $absoluteEarliestYear")
            EnrichmentResult(
                albumTitle = sanitizeMetadata(page.album.title),
                albumId = page.album.browseId,
                albumArtist = page.album.artists?.firstOrNull()?.name, // Capture the primary album artist
                year = absoluteEarliestYear,
                standardYear = standardYear,
                thumbnailUrl = page.album.thumbnail,
                albumPage = page,
                trackNumber = trackNumber
            )
        }
    }
}
