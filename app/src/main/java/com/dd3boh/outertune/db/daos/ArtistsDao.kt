package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.ui.utils.resize
import com.zionhuang.innertube.pages.ArtistPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime

/*
 * Logic related to artists entities and their mapping
 */

@Dao
interface ArtistsDao {

    // region Gets
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND song.inLibrary IS NOT NULL
        WHERE artist.id = :id
        GROUP BY artist.id
    """)
    fun artist(id: String): Flow<Artist?>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artistById(id: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE name = :name  COLLATE NOCASE")
    fun artistByName(name: String): ArtistEntity?

    @Query("SELECT * FROM artist WHERE name LIKE '%' || :name || '%'")
    fun artistsByNameFuzzy(name: String): List<ArtistEntity>

    @Query("SELECT * FROM artist ORDER BY name ASC")
    fun allArtistsRaw(): List<ArtistEntity>

    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.name LIKE '%' || :query || '%' AND (song.inLibrary IS NOT NULL OR song.dateDownload IS NOT NULL)
        GROUP BY artist.id
        HAVING songCount > 0
        ORDER BY artist.bookmarkedAt ASC
        LIMIT :previewSize
    """)
    fun searchArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>

    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.name LIKE '%' || :query || '%' AND song.inLibrary IS NOT NULL AND song.isLocal
        GROUP BY artist.id
        HAVING songCount > 0
        LIMIT :previewSize
    """)
    fun searchLocalArtists(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Artist>>


    @Transaction
    @Query("""
        SELECT song.* 
        FROM song_artist_map JOIN song ON song_artist_map.songId = song.id 
        WHERE song_artist_map.artistId IN (SELECT id FROM artist WHERE name LIKE '%' || :query || '%') AND song.inLibrary IS NOT NULL 
        LIMIT :previewSize
    """)
    fun searchArtistSongs(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<Song>>

    @Query("SELECT * FROM artist WHERE name LIKE '%' || :query || '%' LIMIT :previewSize")
    fun artistsByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal != 1")
    fun allRemoteArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE isLocal = 1")
    fun allLocalArtists(): List<ArtistEntity>

    @Query("SELECT COUNT(*) FROM artist WHERE isLocal = 1 AND (channelId IS NULL OR channelId = '')")
    fun getUnlinkedLocalArtistCount(): Int

    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
            LEFT JOIN (
                SELECT 
                    song AS songId, 
                    SUM(count) AS songTotalPlays
                FROM playCount
                WHERE year > :fromYear OR (year = :fromYear AND month >= :fromMonth)
                GROUP BY song
            ) AS pc ON sam.songId = pc.songId
        WHERE song.inLibrary IS NOT NULL
        GROUP BY artist.id
        ORDER BY SUM(pc.songTotalPlays) DESC
        LIMIT :limit
    """)
    fun mostPlayedArtists(fromYear: Int, fromMonth: Int, limit: Int = 6): Flow<List<Artist>>

    @Transaction
    @Query("""
        SELECT
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.bookmarkedAt IS NOT NULL
        GROUP BY artist.id
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    fun getRandomArtists(limit: Int): List<Artist>

    @RawQuery(observedEntities = [ArtistEntity::class, SongArtistMap::class, SongEntity::class])
    fun _getArtists(query: SupportSQLiteQuery): Flow<List<Artist>>

    fun artists(filter: ArtistFilter, sortType: ArtistSortType, descending: Boolean, localOnly: Boolean? = null): Flow<List<Artist>> {
        val effectiveDescending = when (sortType) {
            ArtistSortType.NAME -> !descending
            else -> descending
        }
            val sortOrder = if (effectiveDescending) "DESC" else "ASC"


        // --- DEFINITIVE FIX FOR YT LINK STATUS: Grouping Auto + Manual Linked together ---
        val orderBy = when (sortType) {
            ArtistSortType.CREATE_DATE -> "artist.rowId $sortOrder"
            ArtistSortType.NAME -> "artist.name COLLATE NOCASE $sortOrder"
            ArtistSortType.SONG_COUNT -> "songCount $sortOrder"
            ArtistSortType.YT_LINK_STATUS ->
                // Linked Logic:
                // A song is LINKED if: it has a channelId OR its id starts with 'UC' (Auto-linked)
                // We assign 0 to Linked, 1 to Unlinked.
                // ASC (0, 1) puts ALL Linked on top. DESC (1, 0) puts Unlinked on top.
                """(CASE 
                    WHEN (artist.channelId IS NOT NULL AND artist.channelId != '') 
                      OR (artist.id LIKE 'UC%') 
                    THEN 0 ELSE 1 END) $sortOrder"""
        }

        val where = when (filter) {
            ArtistFilter.DOWNLOADED -> "song.dateDownload IS NOT NULL"
            ArtistFilter.LIBRARY -> "song.inLibrary IS NOT NULL"
            ArtistFilter.LIKED -> "artist.bookmarkedAt IS NOT NULL"
        } + if (localOnly == null) {
            ""
        } else if (localOnly) {
            // Keep artists in "Local" view if they have at least one local song
            " AND song.isLocal = 1"
        } else {
            " AND artist.isLocal = 0"
        }

        val having = when (filter) {
            ArtistFilter.DOWNLOADED -> "AND downloadCount > 0"
            else -> ""
        }

        val query = SimpleSQLiteQuery("""
            SELECT 
                artist.*,
                COUNT(song.id) AS songCount,
                SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
            FROM artist
                LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
                LEFT JOIN song ON sam.songId = song.id
            WHERE ${where.trimStart(' ', 'A', 'N', 'D')}
            GROUP BY artist.id
            HAVING songCount >= 0 $having
            ORDER BY $orderBy
        """)

        return _getArtists(query).map { artists ->
            artists.filter { it.artist.isYouTubeArtist || it.artist.isLocal }
        }
    }


    fun artistsInLibraryAsc() = artists(ArtistFilter.LIBRARY, ArtistSortType.CREATE_DATE, false)
    fun artistsBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false)
    fun artistsLocalBookmarkedAsc() = artists(ArtistFilter.LIKED, ArtistSortType.CREATE_DATE, false, true)

    @Transaction
    @Query("""
        SELECT 
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id
        WHERE artist.isLocal = 1
        GROUP BY artist.id
        ORDER BY artist.name ASC
    """)
    fun localArtistsByName(): List<Artist>

    @Transaction
    @Query("""
        SELECT
            artist.*,
            COUNT(song.id) AS songCount,
            SUM(CASE WHEN song.dateDownload IS NOT NULL THEN 1 ELSE 0 END) AS downloadCount
        FROM artist
            LEFT JOIN song_artist_map sam ON artist.id = sam.artistId
            LEFT JOIN song ON sam.songId = song.id AND song.inLibrary IS NOT NULL
        WHERE artist.id IN (:ids)
        GROUP BY artist.id
    """)
    suspend fun artistsByIds(ids: List<String>): List<Artist>
    // endregion

    // region Artist Songs Sort
    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY inLibrary")
    fun artistSongsByCreateDateAsc(artistId: String): Flow<List<Song>>

    @Transaction
    @Query("SELECT song.* FROM song_artist_map JOIN song ON song_artist_map.songId = song.id WHERE artistId = :artistId AND inLibrary IS NOT NULL ORDER BY title COLLATE NOCASE ASC")
    fun artistSongsByNameAsc(artistId: String): Flow<List<Song>>

    fun artistSongs(artistId: String, sortType: ArtistSongSortType, descending: Boolean) =
        when (sortType) {
            ArtistSongSortType.CREATE_DATE -> artistSongsByCreateDateAsc(artistId)
            ArtistSongSortType.NAME -> artistSongsByNameAsc(artistId)
        }.map { it.reversed() }
    // endregion
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongArtistMap)
    // endregion

    // region Updates
    @Update
    fun update(artist: ArtistEntity)

    @Transaction
    fun update(artist: ArtistEntity, artistPage: ArtistPage) {
        update(
            artist.copy(
                name = artistPage.artist.title,
                thumbnailUrl = artistPage.artist.thumbnail?.resize(544, 544),
                lastUpdateTime = LocalDateTime.now()
            )
        )
    }

    @Transaction
    @Query("UPDATE song_artist_map SET artistId = :newId WHERE artistId = :oldId")
    fun updateSongArtistMap(oldId: String, newId: String)

    @Query("SELECT * FROM song_artist_map WHERE artistId = :artistId")
    fun getSongArtistMapsByArtist(artistId: String): List<SongArtistMap>

    @Delete
    fun delete(maps: List<SongArtistMap>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(maps: List<SongArtistMap>)


    // endregion

    // region Deletes
    @Delete
    fun delete(artist: ArtistEntity)

    @Query("""
        DELETE FROM Artist
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_artist_map
            WHERE song_artist_map.artistId = :artistId
        )
        AND id = :artistId
    """)
    fun safeDeleteArtist(artistId: String)

    @Transaction
    @Query("DELETE FROM artist WHERE isLocal = 1")
    fun nukeLocalArtists()
    // endregion
}