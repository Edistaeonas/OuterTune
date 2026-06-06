package com.dd3boh.outertune.db

import androidx.compose.ui.geometry.isEmpty
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.dd3boh.outertune.db.daos.AlbumsDao
import com.dd3boh.outertune.db.daos.ArtistsDao
import com.dd3boh.outertune.db.daos.BlacklistedArtistsDao
import com.dd3boh.outertune.db.daos.CustomLinkArtistsDao
import com.dd3boh.outertune.db.daos.NoLinkArtistsDao
import com.dd3boh.outertune.db.daos.PlaylistsDao
import com.dd3boh.outertune.db.daos.QueueDao
import com.dd3boh.outertune.db.daos.SongsDao
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.EventWithSong
import com.dd3boh.outertune.db.entities.FormatEntity
import com.dd3boh.outertune.db.entities.GenreEntity
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.db.entities.RecentActivityEntity
import com.dd3boh.outertune.db.entities.RecentActivityType
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.SongGenreMap
import com.dd3boh.outertune.extensions.toSQLiteQuery
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.coroutines.flow.Flow
import kotlin.text.any
import kotlin.text.find
import kotlin.text.lowercase

data class SongIdentity(val artistName: String?, val title: String)

@Dao
interface DatabaseDao : SongsDao, AlbumsDao, ArtistsDao, PlaylistsDao, QueueDao, BlacklistedArtistsDao, NoLinkArtistsDao, CustomLinkArtistsDao {

    @Transaction
    @Query("""
        SELECT song.*
        FROM (SELECT *, COUNT(1) AS referredCount
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN song ON song.id = map.relatedSongId
        WHERE songId IN (SELECT songId
                         FROM (SELECT songId
                               FROM event
                               ORDER BY ROWID DESC
                               LIMIT 5)
                         UNION
                         SELECT songId
                         FROM (SELECT songId
                               FROM event
                               WHERE timestamp > :now - 86400000 * 7
                               GROUP BY songId
                               ORDER BY SUM(playTime) DESC
                               LIMIT 5)
                         UNION
                         SELECT id
                         FROM (SELECT id
                               FROM song
                               LIMIT 10))
        ORDER BY referredCount DESC
        LIMIT 100
    """)
    fun quickPicks(now: Long = System.currentTimeMillis()): Flow<List<Song>>



    @Query("""
        SELECT artist.name as artistName, song.title as title 
        FROM event 
        JOIN song ON event.songId = song.id
        LEFT JOIN song_artist_map ON song.id = song_artist_map.songId
        LEFT JOIN artist ON song_artist_map.artistId = artist.id
        WHERE song_artist_map.position = 0 OR song_artist_map.position IS NULL
        ORDER BY event.timestamp DESC 
        LIMIT :limit
    """)
    fun getRecentSongIdentities(limit: Int): List<SongIdentity>

    @Query("""
        SELECT * FROM song
        WHERE isLocal = 1
        AND id NOT IN (
            SELECT songId FROM song_artist_map WHERE artistId NOT IN (SELECT id FROM artist)
        )
        AND id NOT IN (
            SELECT songId FROM song_album_map WHERE albumId NOT IN (SELECT id FROM album)
        )
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    abstract fun getRandomLocalSongEntities(limit: Int): List<SongEntity>

    @Transaction
    @Query("SELECT * FROM song WHERE dateDownload IS NOT NULL AND isLocal = 0")
    abstract fun getAllDownloadedSongsDiagnostic(): List<Song>

    @Query("""
        SELECT * FROM song
        WHERE dateDownload IS NOT NULL
        AND isLocal = 0 
        AND id NOT IN (
            SELECT songId FROM song_artist_map WHERE artistId NOT IN (SELECT id FROM artist)
        )
        AND id NOT IN (
            SELECT songId FROM song_album_map WHERE albumId NOT IN (SELECT id FROM album)
        )
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    abstract fun getRandomDownloadedSongEntities(limit: Int): List<SongEntity>

    @Query("SELECT COUNT(*) FROM song WHERE dateDownload IS NOT NULL AND id NOT IN (SELECT songId FROM song_artist_map)")
    abstract fun getOrphanedDownloadCount(): Int

    @Query("""
        SELECT * FROM song
        WHERE id NOT IN (
            SELECT songId FROM song_artist_map WHERE artistId NOT IN (SELECT id FROM artist)
        )
        AND id NOT IN (
            SELECT songId FROM song_album_map WHERE albumId NOT IN (SELECT id FROM album)
        )
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    abstract fun getRandomSongEntities(limit: Int): List<SongEntity>

    @Query("""
        SELECT * FROM song
        WHERE liked = 1        AND id NOT IN (
            SELECT songId FROM song_artist_map WHERE artistId NOT IN (SELECT id FROM artist)
        )
        AND id NOT IN (
            SELECT songId FROM song_album_map WHERE albumId NOT IN (SELECT id FROM album)
        )
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    abstract fun getRandomLikedSongEntities(limit: Int): List<SongEntity>

    @Transaction
    @Query("""
        SELECT *, (
            SELECT COUNT(songId) FROM song_album_map
            INNER JOIN song ON song.id = song_album_map.songId
            WHERE song_album_map.albumId = album.id AND song.dateDownload IS NOT NULL
        ) AS downloadCount
        FROM album
        WHERE bookmarkedAt IS NOT NULL
        ORDER BY RANDOM()
        LIMIT :limit
    """)
    abstract fun getRandomLikedAlbums(limit: Int): List<Album>

    @Query("""
        SELECT * FROM song
        WHERE id IN (SELECT songId FROM song_album_map WHERE albumId = :albumId)
        ORDER BY RANDOM()
        LIMIT 1
    """)
    abstract fun getRandomSongFromAlbum(albumId: String): SongEntity?

    @Transaction
    @Query("""
        SELECT artist.*,
            (SELECT COUNT(songId) FROM song_artist_map WHERE artistId = artist.id) AS songCount,
            (SELECT COUNT(songId) FROM song_artist_map
                INNER JOIN song ON song.id = song_artist_map.songId
                WHERE song_artist_map.artistId = artist.id AND song.dateDownload IS NOT NULL
            ) AS downloadCount
        FROM artist
        WHERE artist.name IN (
            SELECT name FROM artist WHERE bookmarkedAt IS NOT NULL
            UNION
            SELECT T2.name FROM song_artist_map AS T1 JOIN artist AS T2 ON T1.artistId = T2.id WHERE T1.songId IN (SELECT id FROM song WHERE isLocal = 1)
            UNION
            SELECT T2.name FROM song_artist_map AS T1 JOIN artist AS T2 ON T1.artistId = T2.id WHERE T1.songId IN (SELECT id FROM song WHERE liked = 1)
            UNION
            SELECT T2.name FROM album_artist_map AS T1 JOIN artist AS T2 ON T1.artistId = T2.id WHERE T1.albumId IN (SELECT id FROM album WHERE bookmarkedAt IS NOT NULL)
        )
        GROUP BY artist.name
    """)
    fun getMasterArtistList(): List<Artist>

    @Transaction
    @Query("SELECT * FROM song WHERE isLocal = 1")
    fun allLocalSongsFlow(): Flow<List<Song>>


    @Query("SELECT * FROM format WHERE id = :id")
    fun format(id: String?): Flow<FormatEntity?>

    @Query("SELECT * FROM lyrics WHERE id = :id")
    fun lyrics(id: String?): Flow<LyricsEntity?>

    @Transaction
    @Query("SELECT * FROM event ORDER BY rowId DESC")
    fun events(): Flow<List<EventWithSong>>

    @Query("DELETE FROM event")
    fun clearListenHistory()

    @Query("SELECT * FROM search_history WHERE `query` LIKE :query || '%' ORDER BY id DESC")
    fun searchHistory(query: String = ""): Flow<List<SearchHistory>>

    @Query("DELETE FROM search_history")
    fun clearSearchHistory()

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    fun hasRelatedSongs(songId: String): Boolean

    @Transaction
    @Query(
        """
        SELECT song.*
        FROM (SELECT *
              FROM related_song_map
              GROUP BY relatedSongId) map
                 JOIN
             song
             ON song.id = map.relatedSongId
        WHERE songId = :songId
        """
    )
    fun relatedSongs(songId: String): List<Song>

    @Query("""
        SELECT * FROM genre
        WHERE genre.isLocal = 1
        ORDER BY genre.title ASC
    LIMIT :previewSize""")
    fun allLocalGenresByName(previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Query("SELECT * FROM genre WHERE id = :id")
    fun genreById(id: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title = :name")
    fun genreByName(name: String): GenreEntity?

    @Query("SELECT * FROM genre WHERE title LIKE '%' || :query || '%' LIMIT :previewSize")
    fun genreByNameFuzzy(query: String, previewSize: Int = Int.MAX_VALUE): List<GenreEntity>

    @Transaction
    @Query("UPDATE song_genre_map SET genreId = :newId WHERE genreId = :oldId")
    fun updateSongGenreMap(oldId: String, newId: String)

    @Query("UPDATE song_artist_map SET artistId = :newId WHERE artistId = :oldId")
    override fun updateSongArtistMap(oldId: String, newId: String)

    @Query(
        """
        DELETE FROM genre
        WHERE NOT EXISTS (
            SELECT 1
            FROM song_genre_map
            WHERE song_genre_map.genreId = :genreId
        )
        AND id = :genreId
    """
    )
    fun safeDeleteGenre(genreId: String)


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: SongGenreMap)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchHistory: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(event: Event)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(map: RelatedSongMap)

    @Transaction
    fun insert(mediaMetadata: MediaMetadata, block: (SongEntity) -> SongEntity = { it }) {
        android.util.Log.d("DatabaseDao", "insert(MediaMetadata) triggered for: '${mediaMetadata.title}' (ID: ${mediaMetadata.id})")


        if (insert(mediaMetadata.toSongEntity().let(block)) == -1L) return
        mediaMetadata.artists.forEachIndexed { index, artist ->
            val artistId = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId()
            insert( // TODO: use upsert???
                ArtistEntity(
                    id = artistId,
                    name = artist.name,
                    isLocal = artist.isLocal
                )
            )
            insert(
                SongArtistMap(
                    songId = mediaMetadata.id,
                    artistId = artistId,
                    position = index
                )
            )
        }
        mediaMetadata.genre?.forEachIndexed { index, genre ->
            val genreId = genreByName(genre.title)?.id ?: GenreEntity.generateGenreId()
            insert( // TODO: use upsert???
                GenreEntity(
                    id = genreId,
                    title = genre.title,
                    isLocal = genre.isLocal
                )
            )
            insert(
                SongGenreMap(
                    songId = mediaMetadata.id,
                    genreId = genreId,
                    index = index
                )
            )
        }

//        mediaMetadata.album?.let {
//            val album = albumsByName(it.title)
//            // CORRECTED: Use the correct ID generator for albums. Used to be val albumId = album?.id ?: GenreEntity.generateGenreId()
//            val albumId = album?.id ?: AlbumEntity.generateAlbumId()
//            upsert(
//                AlbumEntity(
//                    id = albumId,
//                    title = it.title,
//                    thumbnailUrl = album?.thumbnailUrl?: mediaMetadata.thumbnailUrl,
//                    songCount = 1,
//                    duration = (album?.duration ?: 0) + mediaMetadata.duration,
//                    isLocal = it.isLocal
//                )
//            )
//            insert(
//                SongAlbumMap(
//                    songId = mediaMetadata.id,
//                    albumId = albumId,
//                    index = album?.songCount ?: 0
//                )
//            )
//        }

        mediaMetadata.album?.let { albumTag ->
            val albums = albumsByName(albumTag.title)

            // IMPROVED: Match by name AND Album Artist (if available) to ensure correct grouping.
            val existingAlbum = albums.find { existing ->
                if (mediaMetadata.albumArtist != null) {
                    val existingArtists = getArtistsByAlbum(existing.id)
                    existingArtists.any { it.name.equals(mediaMetadata.albumArtist, ignoreCase = true) }
                } else {
                    // Fallback to track artist matching if albumArtist is missing from metadata
                    val songArtistNames = mediaMetadata.artists.map { a -> a.name.lowercase() }.toSet()
                    val existingArtists = getArtistsByAlbum(existing.id)
                    existingArtists.isEmpty() || existingArtists.any { a -> a.name.lowercase() in songArtistNames }
                }
            }

            // Use existing ID if found, trust provided ID if it's stable (YT), otherwise generate new.
            val isStableId = albumTag.id.startsWith("MPREb_") || albumTag.id.contains("_") || albumTag.id.length > 12
            val albumId = existingAlbum?.id ?: if (isStableId) albumTag.id else AlbumEntity.generateAlbumId()

            // Use insert(IGNORE) to prevent renaming existing albums
            insert(
                AlbumEntity(
                    id = albumId,
                    title = albumTag.title,
                    thumbnailUrl = existingAlbum?.thumbnailUrl ?: mediaMetadata.thumbnailUrl,
                    songCount = (existingAlbum?.songCount ?: 0) + 1,
                    duration = (existingAlbum?.duration ?: 0) + mediaMetadata.duration,
                    isLocal = albumTag.isLocal
                )
            )

            insert(
                SongAlbumMap(
                    songId = mediaMetadata.id,
                    albumId = albumId,
                    index = mediaMetadata.trackNumber ?: (existingAlbum?.songCount ?: 0)
                )
            )

            // Link Album to its primary artists if it's a new record
            if (existingAlbum == null) {
                // If we have an explicit album artist, prioritize it for the album record links
                if (mediaMetadata.albumArtist != null) {
                    val artistId = artistByName(mediaMetadata.albumArtist)?.id ?: ArtistEntity.generateArtistId()
                    insert(ArtistEntity(id = artistId, name = mediaMetadata.albumArtist, isLocal = albumTag.isLocal))
                    insert(AlbumArtistMap(albumId, artistId, 0))
                } else {
                    // Otherwise use track artists as before
                    mediaMetadata.artists.forEachIndexed { index, artist ->
                        val artistId = artist.id ?: artistByName(artist.name)?.id ?: return@forEachIndexed
                        insert(AlbumArtistMap(albumId, artistId, index))
                    }
                }
            }
        }

    }

    @Transaction
    fun insert(albumPage: AlbumPage) {
        if (insert(AlbumEntity(
                id = albumPage.album.browseId,
                playlistId = albumPage.album.playlistId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 }
            )) == -1L
        ) return
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists
            ?.map { artist ->
                ArtistEntity(
                    id = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId(),
                    name = artist.name
                )
            }
            ?.onEach(::insert)
            ?.mapIndexed { index, artist ->
                AlbumArtistMap(
                    albumId = albumPage.album.browseId,
                    artistId = artist.id,
                    order = index
                )
            }
            ?.forEach(::insert)
    }

    @Transaction
    fun update(album: AlbumEntity, albumPage: AlbumPage) {
        update(
            album.copy(
                id = albumPage.album.browseId,
                playlistId = albumPage.album.playlistId,
                title = albumPage.album.title,
                year = albumPage.album.year,
                thumbnailUrl = albumPage.album.thumbnail,
                songCount = albumPage.songs.size,
                duration = albumPage.songs.sumOf { it.duration ?: 0 }
            )
        )
        albumPage.songs.map(SongItem::toMediaMetadata)
            .onEach(::insert)
            .mapIndexed { index, song ->
                SongAlbumMap(
                    songId = song.id,
                    albumId = albumPage.album.browseId,
                    index = index
                )
            }
            .forEach(::upsert)
        albumPage.album.artists
            ?.map { artist ->
                ArtistEntity(
                    id = artist.id ?: artistByName(artist.name)?.id ?: ArtistEntity.generateArtistId(),
                    name = artist.name
                )
            }
            ?.onEach(::insert)
            ?.mapIndexed { index, artist ->
                AlbumArtistMap(
                    albumId = albumPage.album.browseId,
                    artistId = artist.id,
                    order = index
                )
            }
            ?.forEach(::insert)
    }

    @Upsert
    fun upsert(lyrics: LyricsEntity)

    @Upsert
    fun upsert(format: FormatEntity)

    @Delete
    fun delete(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics where id = :id")
    fun deleteLyricById(id: String)

    @Delete
    fun delete(searchHistory: SearchHistory)

    @Delete
    fun delete(event: Event)


    /**
     * WARNING: This removes all queue song data and re-adds the queue. Did you mean to use updateQueue()?
     */
    @Transaction
    fun saveQueue(mq: MultiQueueObject) {
        if (mq.queue.isEmpty()) {
            return
        }

        insert(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )

        deleteAllQueueSongs(mq.id)
        // insert songs

        // why does kotlin not have for i loop???
        var i = 0
        while (i < mq.getSize()) {
            insert(mq.queue[i]) // make sure song exists
            insert(
                QueueSongMap(
                    queueId = mq.id,
                    songId = mq.queue[i].id,
                    index = i.toLong(),
                    shuffledIndex = mq.queue[i].shuffleIndex.toLong(),
                    parentArtist = mq.queue[i].parentArtist
                )
            )
            i ++
        }
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(item: RecentActivityEntity)

    @Delete
    fun delete(item: RecentActivityEntity)

    @Query("DELETE FROM recent_activity")
    fun clearRecentActivity()

    @Transaction
    fun insertRecentActivityItem(item: YTItem) {
        when (item) {
            is AlbumItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.browseId,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ALBUM,
                        playlistId = item.playlistId,
                        radioPlaylistId = null,
                        shufflePlaylistId = null
                    )
                )
            }

            is PlaylistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.PLAYLIST,
                        playlistId = item.id,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            is ArtistItem -> {
                insert(
                    RecentActivityEntity(
                        id = item.id,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        explicit = item.explicit,
                        shareLink = item.shareLink,
                        type = RecentActivityType.ARTIST,
                        playlistId = item.playEndpoint?.playlistId,
                        radioPlaylistId = item.radioEndpoint?.playlistId,
                        shufflePlaylistId = item.shuffleEndpoint?.playlistId
                    )
                )
            }

            else -> {
                // do nothing
            }
        }
    }

    @Query("SELECT * FROM recent_activity ORDER BY date DESC")
    fun recentActivity(): Flow<List<RecentActivityEntity>>

    /**
     * Nukes
     */

    @Transaction
    @Query("DELETE FROM genre WHERE isLocal = 1")
    fun nukeLocalGenre()

    @Transaction
    @Query("""
DELETE FROM format 
WHERE format.id IS NOT NULL 
AND NOT EXISTS (
    SELECT 1 FROM song WHERE song.id = format.id
);
    """)
    fun nukeDanglingFormatEntities()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id IN (SELECT song.id FROM song WHERE song.isLocal)")
    fun nukeLocalLyrics()

    @Transaction
    @Query("DELETE FROM lyrics WHERE lyrics.id NOT IN (SELECT song.id FROM song)")
    fun nukeDanglingLyrics()

    @Transaction
    @Query("DELETE FROM playlist WHERE isLocal = 0")
    fun nukeRemotePlaylists()

    @Transaction
    fun nukeLocalData() {
        nukeLocalSongs()
        nukeLocalArtists()
        nukeLocalAlbums()
        nukeLocalGenre()
    }

    @RawQuery
    fun raw(supportSQLiteQuery: SupportSQLiteQuery): Int

    fun checkpoint() {
        raw("PRAGMA wal_checkpoint(FULL)".toSQLiteQuery())
    }
}
