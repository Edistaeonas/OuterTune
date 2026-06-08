/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.SQLException
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioOffloadSupportProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioDecoderKey
import com.dd3boh.outertune.constants.AudioGaplessOffloadKey
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioOffloadKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.GlobalRadioArtistsCountKey
import com.dd3boh.outertune.constants.GlobalRadioLocalAndLikedSongsCountKey
import com.dd3boh.outertune.constants.GlobalRadioSongsCountKey
import com.dd3boh.outertune.constants.GlobalRadioUseLocalKey
import com.dd3boh.outertune.constants.GlobalRadioUseOnlineKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.RadioDJEnabledKey
import com.dd3boh.outertune.constants.RadioDJLanguageKey
import com.dd3boh.outertune.constants.RadioDJStyleKey
import com.dd3boh.outertune.constants.SYSTEM_DEFAULT
import com.dd3boh.outertune.constants.MAX_PLAYER_CONSECUTIVE_ERR
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleLike
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleShuffle
import com.dd3boh.outertune.constants.MediaSessionConstants.CommandToggleStartRadio
import com.dd3boh.outertune.constants.PauseListenHistoryKey
import com.dd3boh.outertune.constants.PauseRemoteListenHistoryKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.constants.PlayerVolumeKey
import com.dd3boh.outertune.constants.RepeatModeKey
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.VrVerKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.RelatedSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.di.AppModule.PlayerCache
import com.dd3boh.outertune.di.DownloadCache
import com.dd3boh.outertune.extensions.SilentHandler
import com.dd3boh.outertune.extensions.collect
import com.dd3boh.outertune.extensions.collectLatest
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.extensions.setOffloadEnabled
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.GlobalRadioHistory
import com.dd3boh.outertune.playback.queues.GlobalRadioQueue
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.MetadataEnricher
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.dd3boh.outertune.utils.YTPlayerUtils.isInternetActuallyAvailable
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.playerCoroutine
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.sanitizeMetadata
import com.google.common.util.concurrent.MoreExecutors
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_ALT1_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_ALT2_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_FALLBACK_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_LATEST_VERSION
import dagger.hilt.android.AndroidEntryPoint
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import kotlin.math.pow
import kotlin.system.exitProcess

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService : MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    val TAG = MusicService::class.simpleName.toString()

    private var currentContinuableQueue: Queue? = null
    private var currentFullSongList: List<Song>? = null
    private var globalRadioHistory: GlobalRadioHistory? = null
    val AutoPlayRestartKey = booleanPreferencesKey("auto_play_restart")
    private var firstNetworkErrorTime = 0L


    @Inject
    lateinit var database: MusicDatabase
    private val scope = CoroutineScope(Dispatchers.Main)
    private val offloadScope = CoroutineScope(playerCoroutine)

    // Critical player components
    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private val binder = MusicBinder()
    private lateinit var connectivityManager: ConnectivityManager
    private var networkRetryCount = 0

    val qbInit = MutableStateFlow(false)
    var queueBoard = QueueBoard(this, maxQueues = 1)
    var queuePlaylistId: String? = null

    private var isFirstPlayAttempt = true

    private var isManualResetting = false

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    val songUrlCache = HashMap<String, Pair<String, Long>>()

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    // Player components
    @Inject
    lateinit var syncUtils: SyncUtils

    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(true)

    lateinit var sleepTimer: SleepTimer

    // Player vars
    val currentMediaMetadata = MutableStateFlow<com.dd3boh.outertune.models.MediaMetadata?>(
        null
    )

    private val currentSong = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.song(mediaMetadata?.id)
    }.stateIn(offloadScope, SharingStarted.Lazily, null)

    private val currentFormat = currentMediaMetadata.flatMapLatest { mediaMetadata ->
        database.format(mediaMetadata?.id)
    }

    private val normalizeFactor = MutableStateFlow(1f)

    private val audioDecoder =
        dataStore.get(AudioDecoderKey, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    private val isGaplessOffloadAllowed = dataStore.get(AudioGaplessOffloadKey, false)
    val playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))
    private val muteForDJ = MutableStateFlow(false)

    private var isAudioEffectSessionOpened = false

    var consecutivePlaybackErr = 0
    private var consecutiveUrlErrors = 0
    private var globalRadioRetryJob: Job? = null
    private var firstFailedSongIndex: Int = -1
    private var backgroundEnrichmentJob: Job? = null

    private var metadataUpdateJob: Job? = null

    // Track if YouTube session is ready
    private val isYouTubeReady = MutableStateFlow(false)

    private var preCacheJob: Job? = null
    private val preCacheLock = kotlinx.coroutines.sync.Mutex()

    private val globalRadioLoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val isGlobalRadio = queuePlaylistId?.startsWith("GLOBAL_RADIO") == true
            // If in Global Radio and the network is down or we have an IO error, fail immediately (skip retries)
//            if (isGlobalRadio && (!isNetworkConnected.value || loadErrorInfo.exception is IOException)) {
//                Log.w(TAG, "Global Radio: Network error detected. Disabling retries for faster fallback.")
//                return C.TIME_UNSET // This triggers immediate failure
//            }
            // If in Global Radio, allow a few retries even if reported offline to handle handoff (Wi-Fi <-> Cellular)
            if (isGlobalRadio && !isNetworkConnected.value && loadErrorInfo.errorCount < 3) {
                Log.w(TAG, "GLOBAL RADIO: Network error detected. Allowing a 2s delay between retries.!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                return 2000 // 2s delay between retries
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            if (queuePlaylistId?.startsWith("GLOBAL_RADIO") == true && !isNetworkConnected.value) {
                return 3 // Try at least 3 times before failing  (0 was too aggressive)
            }
            return super.getMinimumLoadableRetryCount(dataType)
        }
    }

    private var isShowingTimedTags = false
    private var timedTagJob: Job? = null

    private var radioDJ: RadioDJ? = null

    override fun onCreate() {
        instance = this
        Log.i(TAG, " *********** Starting MusicService **************")
        radioDJ = RadioDJ(this)
        // --- ADD THIS: Set process priority to Background Audio ---
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        super.onCreate()

        // --- DEBUG: CALL FACTORY SEPARATELY ---
        val debugDataSourceFactory = createDataSourceFactory()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(debugDataSourceFactory)
                    .setLoadErrorHandlingPolicy(globalRadioLoadErrorHandlingPolicy)
            )
            .setRenderersFactory(createRenderersFactory(isGaplessOffloadAllowed))
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            )
            .setSeekBackIncrementMs(5000)
            .setSeekForwardIncrementMs(5000)
            .build()
            .apply {
                // listeners
                addListener(this@MusicService)
                sleepTimer = SleepTimer(scope, this)
                addListener(sleepTimer)
                addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

                // misc
                setOffloadEnabled(dataStore.get(AudioOffloadKey, false))
            }


        // ---Prevent "Stuck at 00:00:00" ---
        // On first startup, "warm up" the YouTube client on a background thread.
        // This prevents a deadlock when the player later tries to resolve a URL.

        //   --- TASK 1: Session Warm-up   ---
        scope.launch(Dispatchers.IO) {
            if (YouTube.visitorData == null) {
                Log.i(TAG, "First start: Warming up YouTube session...")
                runCatching {
                    // Forcing a home() call is the best way to get a fresh visitorData
                    YouTube.home()
                    // 2. Perform a tiny player request to "prime" the /player endpoint
                    // This creates the persistent TCP/TLS connection before the user clicks play.
                    YouTube.player("dQw4w9WgXcQ", null, YouTubeClient.ANDROID_VR_NO_AUTH)
                }.onSuccess {
                    Log.i(TAG, "YouTube session ready. VisitorData: ${YouTube.visitorData}")
                    isYouTubeReady.value = true

                }.onFailure {
                    Log.e(TAG, "YouTube warm-up failed", it)
                }
            } else {
                isYouTubeReady.value = true
            }
        }

        // --- TASK 2: SMART HANG WATCHDOG (Timer-based) ---
        scope.launch {
            var lastHangingId: String? = null
            var lastPosition = -1L
            var stagnantCount = 0
            var bufferingStagnantCount = 0
            var watchdogRetryCount = 0

            // Use a continuous loop instead of event listeners to detect "no progress"
            while (isActive) {
                delay(2000) // Check every 2 seconds

                if (isManualResetting) continue

                // 1. Stand down condition: idle or paused
                if (player.playbackState == Player.STATE_IDLE ||
                    player.playbackState == Player.STATE_ENDED ||
                    !player.playWhenReady) {
                    stagnantCount = 0
                    bufferingStagnantCount = 0
                    if (player.currentMediaItem?.mediaId != lastHangingId) {
                        watchdogRetryCount = 0
                        lastHangingId = null
                    }
                    continue
                }

                val currentMetadata = player.currentMetadata
                val isOnlineSong = currentMetadata?.isLocal != true
                val currentId = player.currentMediaItem?.mediaId
                val currentPos = player.currentPosition

                // 2. Connectivity State Correction
                if (!isNetworkConnected.value && isOnlineSong) {
                    if (isInternetActuallyAvailable()) {
                        isNetworkConnected.value = true
                    } else {
                        continue
                    }
                }

                // 3. Counter Logic
                // Stuck at 0:00 (Buffering)
                if (player.playbackState == Player.STATE_BUFFERING && currentPos <= 0L) {
                    bufferingStagnantCount++
                } else {
                    bufferingStagnantCount = 0
                }

                // Stagnant playback (Position not moving despite state being non-idle)
                // We ignore this if an error is already present (handled by onPlayerError)
                if (player.playerError == null && currentPos > 0 && currentPos == lastPosition) {
                    stagnantCount++
                } else {
                    stagnantCount = 0
                }
                lastPosition = currentPos

                // 4. Threshold Detection (~24s for start-up, ~16s for frozen playback)
                if (bufferingStagnantCount > 12 || stagnantCount > 8) {
                    Log.w(TAG, "WATCHDOG: Progress hang detected for $currentId (Stagnant=$stagnantCount, Buffering=$bufferingStagnantCount).")

                    val isGlobalRadio = queuePlaylistId?.startsWith("GLOBAL_RADIO") == true

                    if (currentId == lastHangingId && watchdogRetryCount >= 1) {
                        if (isGlobalRadio) {
                            // --- NEW: Check if the hang is a "Fake" network issue ---
                            val internetIsBack = isInternetActuallyAvailable()
                            if (internetIsBack) {
                                Log.e(TAG, "WATCHDOG: Internet is UP but resolution is hanging. Forcing session reset.")
                                forceYoutubeReset(rotate = true, clearCookies = false, autoPlay = true)
                                return@launch
                            } else {
                                Log.e(TAG, "WATCHDOG: Truly offline. Skipping to local media.")
                                withContext(Dispatchers.Main) {
                                    val nextLocalIndex = findNextOfflineSongIndex()
                                    if (nextLocalIndex != -1) {
                                        player.seekTo(nextLocalIndex, C.TIME_UNSET)
                                        player.prepare()
                                        player.play()
                                    }
                                }
                                lastHangingId = null
                                watchdogRetryCount = 0
                                continue
                            }
                        } else {
                            Log.e(TAG, "WATCHDOG: Recovery failed twice. Triggering Nuclear Reset.")
                            forceYoutubeReset(rotate = true, clearCookies = false, autoPlay = true)
                        }
                    } else {
                        Log.w(TAG, "WATCHDOG: Attempting flush and retry.")

                        // FIX: Move network pool nuke to IO thread to prevent crash
                        withContext(Dispatchers.IO) {
                            YTPlayerUtils.nukeNetworkPool()
                        }

                        player.stop()
                        lastHangingId = currentId
                        watchdogRetryCount++

                        delay(1000)
                        player.prepare()
                        player.play()
                    }
                }
            }
        }

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            startGlobalArtistRadio = this@MusicService::startGlobalArtistRadio
            toggleLibrary = ::toggleLibrary
        }

        mediaSession = MediaLibrarySession.Builder(this, player, mediaLibrarySessionCallback)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // TODO: do i even want to have smaller art for media notification
            .setBitmapLoader(CoilBitmapLoader(this))
            .build()

        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!

        // Observe the current song from the DB.
        currentSong.collect(scope) { songWithArtists ->
            updateNotification()

            val metadata = songWithArtists?.toMediaMetadata() ?: return@collect
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex == -1) return@collect

            val currentItem = player.getMediaItemAt(currentIndex)
            if (currentItem.mediaId != metadata.id) return@collect

            // REFRESH CHECK: Only trigger if we replaced a "Fallback" with a "Real" value
            val extras = currentItem.mediaMetadata.extras

            // 1. Album Check
            val wasFallback = extras?.getBoolean("isFallbackAlbum", false) == true
            val isNowReal = metadata.album != null && !metadata.album.title.startsWith("§")
            val albumImproved = wasFallback && isNowReal

            // 2. Year Check
            val oldYear = extras?.getString("year") ?: ""
            val newYear = metadata.year?.toString() ?: ""
            val yearAdded = oldYear.isEmpty() && newYear.isNotEmpty()

            // 3. Track Number Check
            val oldTrack = currentItem.mediaMetadata.trackNumber ?: -1
            val newTrack = metadata.trackNumber ?: -1
            val trackAdded = (oldTrack == -1 || oldTrack == 0) && newTrack > 0

            if (albumImproved || yearAdded || trackAdded) {
                Log.i(TAG, "DB Update: Found better metadata for '${metadata.title}'. Refreshing session.")
                updateCurrentMediaMetadata(metadata)
            }
        }

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this@MusicService,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            )
                .apply {
                    setSmallIcon(R.drawable.small_icon)
                }
        )

        // lateinit tasks
        offloadScope.launch {
            Log.i(TAG, "Launching MusicService offloadScope tasks")
            if (!qbInit.value) {
                initQueue()
            }

            combine(playerVolume, normalizeFactor, muteForDJ) { playerVolume, normalizeFactor, mute ->
                if (mute) 0f else playerVolume * normalizeFactor
            }.collectLatest(scope) {
                withContext(Dispatchers.Main) {
                    player.volume = it
                }
            }

            playerVolume.debounce(1000).collect(scope) { volume ->
                dataStore.edit { settings ->
                    settings[PlayerVolumeKey] = volume
                }
            }

            dataStore.data
                .map { it[SkipSilenceKey] ?: false }
                .distinctUntilChanged()
                .collectLatest(scope) {
                    withContext(Dispatchers.Main) {
                        player.skipSilenceEnabled = it
                    }
                }

            combine(
                currentFormat,
                dataStore.data
                    .map { it[AudioNormalizationKey] ?: true }
                    .distinctUntilChanged()
            ) { format, normalizeAudio ->
                format to normalizeAudio
            }.collectLatest(scope) { (format, normalizeAudio) ->
                normalizeFactor.value = if (normalizeAudio && format?.loudnessDb != null) {
                    min(10f.pow(-format.loudnessDb.toFloat() / 20), 1f)
                } else {
                    1f
                }
            }


            // network connectivity
//            try {
//                connectivityObserver.unregister()
//            } catch (e: UninitializedPropertyAccessException) {
//                // lol
//            }

            // network connectivity
            // You can remove the try-catch unregister block as the new Flow
            // handles its own cleanup when the job is cancelled.
            connectivityObserver = NetworkConnectivityObserver(this@MusicService)

            offloadScope.launch {
                connectivityObserver.networkStatus.collect { isConnected ->
                    isNetworkConnected.value = isConnected

                    if (isConnected) {
                        // FIX: Proactively clear stale network sockets to prevent 0:00 hang
                        YTPlayerUtils.nukeNetworkPool()

                        // Re-trigger pre-caching now that internet is back
                        withContext(Dispatchers.Main) {
                            runProactivePreCache()
                        }

                        if (waitingForNetworkConnection.value) {
                            waitingForNetworkConnection.value = false
                            withContext(Dispatchers.Main) {
                                player.prepare()
                                player.play()
                            }
                        }
                    }
                }
            }
        }
//2026.03.09 monitor the downloads
        Log.i(TAG, "call runDownloadReport in onCreate from MusicService")
        runDownloadReport()

        scope.launch {
            // Check if we need to auto-resume after a watchdog restart
            // Read the persistent flag
            val shouldAutoPlay = dataStore.get(AutoPlayRestartKey, false)
            val failedIdKey = androidx.datastore.preferences.core.stringPreferencesKey("failed_media_id")
            val failedId = dataStore.get(failedIdKey, "")

            if (shouldAutoPlay) {
                // 1. Reset the flag immediately so it doesn't auto-play on every manual launch
                dataStore.edit { settings ->
                    settings[AutoPlayRestartKey] = false
                    settings.remove(failedIdKey)
                }

                Log.i(TAG, "AUTO-HEAL: Restart detected. Waiting for queue board...!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

                // 2. Suspend until the saved queue is loaded from the database
                qbInit.first { it == true }

                // Skip the failing track if it's still the current one
                if (failedId.isNotEmpty() && player.currentMediaItem?.mediaId == failedId) {
                    Log.w(TAG, "AUTO-HEAL: Skipping $failedId because it caused the previous restart. !!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                    player.seekToNextMediaItem()
                    player.prepare()
                }

                Log.i(TAG, "AUTO-HEAL: Queue initialized, resuming playback.")

                // 3. Small safety buffer then Play
                delay(1000)
                resumePlaybackAfterRestart()
            }
        }
    }

// 2026.03.09 add this new function to log downloaded songs and call it from the Watchdog or a listener:
    fun runDownloadReport() {
        scope.launch(Dispatchers.IO) {            val downloadedSongs = database.getAllDownloadedSongsDiagnostic()
            Log.d(TAG, "========== DOWNLOADED YOUTUBE SONGS REPORT ==========")
            Log.d(TAG, "Total in DB: ${downloadedSongs.size}")
            downloadedSongs.forEach { song ->
                Log.d(TAG, " > [${song.song.id}] ${song.song.title}")
            }
            Log.d(TAG, "======================================================")
        }
    }

// Library functions


    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        withContext(Dispatchers.IO) {
            val song = database.song(mediaId).first()
            Log.i(TAG, "Starting recoverSong for song = '${song?.song?.title}' by artist: '${song?.artists?.firstOrNull()?.name}'")

            // Update Song Duration (This part is correct and should be kept)
            val duration = song?.song?.duration?.takeIf { it != -1 }
                ?: (playbackData?.videoDetails?.lengthSeconds?.toInt())
                ?: -1
            if (song != null && song.song.duration == -1 && duration != -1) {
                database.update(song.song.copy(duration = duration))
            }

            // The enrichment logic is removed for now to ensure stability.
            // We can revisit fixing this function as a separate task.

            // Fetch Related Songs (This part is existing and should be kept)
            if (!database.hasRelatedSongs(mediaId)) {
                val relatedEndpoint =
                    YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                        ?: return@withContext
                val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return@withContext
                database.query {
                    relatedPage.songs
                        .map(SongItem::toMediaMetadata)
                        .onEach(::insert)
                        .map {
                            RelatedSongMap(
                                songId = mediaId,
                                relatedSongId = it.id
                            )
                        }
                }
            }
        }
    }

    /**
     * Call from Activity after process restart to resume playback.
     * Waits for queue initialization (qbInit) so restored queue gets prepared first.
     */
    fun resumePlaybackAfterRestart() {
        scope.launch(Dispatchers.Main) {
            try {
                // Wait until queue initialization completes (safe place to resume)
                qbInit.first { it }
                Log.i(TAG, "resumePlaybackAfterRestart: qbInit ready, attempting resume.")

                // If player already has items prepared, just play.
                if (player.mediaItemCount > 0) {
                    player.play()
                    return@launch
                }

                // Otherwise, attempt a safe prepare + play so any restored queue becomes active.
                try {
                    player.prepare()
                } catch (e: Exception) {
                    Log.w(TAG, "resumePlaybackAfterRestart: player.prepare() failed", e)
                }
                player.play()
            } catch (e: Exception) {
                Log.e(TAG, "resumePlaybackAfterRestart failed", e)
            }
        }
    }

    fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
        database.query {
            currentSong.value?.let {
                val song = it.song.toggleLike()
                update(song)

                if (!song.isLocal) {
                    syncUtils.likeSong(song)
                }
            }
        }
    }

    fun toggleStartRadio() {
        val mediaMetadata = player.currentMetadata ?: return
        playQueue(YouTubeQueue.radio(mediaMetadata), isRadio = true)
    }

    fun testRadioDJ() {
        val metadata = com.dd3boh.outertune.models.MediaMetadata(
            id = "test",
            title = "Test Song",
            artists = listOf(com.dd3boh.outertune.models.MediaMetadata.Artist(name = "Test Artist", id = null)),
            duration = 180,
            thumbnailUrl = null,
            genre = null
        )
        radioDJ?.speakAnnouncement(
            metadata = metadata,
            style = dataStore.get(RadioDJStyleKey, "natural"),
            language = dataStore.get(RadioDJLanguageKey, SYSTEM_DEFAULT),
            onFinished = {}
        )
    }

    // The Global Artist Radio (new in 2026). Creates a playlist mixing local songs with songs taken
    // from the radio of the artists that are in the library (thus mixing this artist's songs and youtube suggestions.

    fun startGlobalArtistRadio() {
        scope.launch {
            // --- NEW: Check if the radio is allowed to start ---
            val useOnline = dataStore.get(GlobalRadioUseOnlineKey, true)
            val useLocal = dataStore.get(GlobalRadioUseLocalKey, true)

            if (!useOnline && !useLocal) {
                Toast.makeText(
                    this@MusicService,
                    getString(R.string.global_radio_check_settings),
                    Toast.LENGTH_LONG
                ).show()
                return@launch // Stop here if both sources are disabled
            }
            // --- END NEW ---

            // Use a string resource for the Toast message
            Toast.makeText(
                this@MusicService,
                getString(R.string.global_radio_starting),
                Toast.LENGTH_LONG
            ).show()

            // Use a string resource for the playlist title
            val radioTitle = getString(R.string.global_radio_title)
            val uniqueTitle = "$radioTitle - ${System.currentTimeMillis()}"

            playQueue(
                queue = GlobalRadioQueue(
                    db = database,
                    context = this@MusicService, // Pass context
                    localAndLikedSongLimit = dataStore.get(
                        GlobalRadioLocalAndLikedSongsCountKey,
                        10
                    ),
                    albumAndArtistLimit = dataStore.get(GlobalRadioArtistsCountKey, 4),
                    songsPerArtistLimit = dataStore.get(GlobalRadioSongsCountKey, 3)
                ),
                replace = true,
                isRadio = true,
                title = uniqueTitle
            )
        }
    }


    /*** Receives enriched metadata and updates the current media session.
     * This ensures Android Auto and Notifications show the high-res online cover.
     */
    fun updateCurrentMediaMetadata(metadata: com.dd3boh.outertune.models.MediaMetadata,
                                   forceShowOrigin: Boolean = false,
                                   showTags: Boolean = isShowingTimedTags
                                   ) {
        isShowingTimedTags = showTags
        // Cancel the previous refresh routine to prevent concurrent updates from fighting
        metadataUpdateJob?.cancel()

        metadataUpdateJob = scope.launch(Dispatchers.Main) {
            val currentWindowIndex = player.currentMediaItemIndex
            if (currentWindowIndex == -1) return@launch

            // --- THE FIX FOR 0:00 HANG ---
            // If the player is currently buffering at the start of a song, wait a moment.
            // Replacing the item while resolving can cause a silent hang on some devices.
            if (player.playbackState == Player.STATE_BUFFERING && player.currentPosition <= 0L) {
                delay(2000)
            }

            val currentMediaItem = player.getMediaItemAt(currentWindowIndex)
            if (currentMediaItem.mediaId != metadata.id) return@launch

            // --- PERSISTENT ORIGIN LOGIC ---
            // We show origin if forced OR if we are still within the first 20 seconds of a radio track.
            // This ensures that DB updates (like adding a track number) don't hide the origin info prematurely.
            val isRadioTrack = metadata.parentArtist != null
            val inOriginWindow = player.currentPosition < 20000
            val shouldShowOrigin = isRadioTrack && (forceShowOrigin || inOriginWindow)

            Log.i(TAG, "Pushing enriched metadata for ${metadata.title}. Origin: $shouldShowOrigin, Tags: $showTags, Pos: ${player.currentPosition}")

            // 1. Push current state (including Context for string resolution)
            val initialItem = metadata.toMediaItem(
                this@MusicService,
                includeParentArtist = shouldShowOrigin,
                showCommentAndComposer = showTags)
            player.replaceMediaItem(currentWindowIndex, initialItem)

            // 2. If we are showing origin info, schedule the switch to standard display
            if (shouldShowOrigin) {
                val remainingDelay = (20000 - player.currentPosition).coerceAtMost(20000).coerceAtLeast(0)
                delay(remainingDelay)

                // Re-verify identity and position after delay
                if (player.currentMediaItem?.mediaId == metadata.id) {
                    val standardItem = metadata.toMediaItem(
                        this@MusicService,
                        includeParentArtist = false,
                        showCommentAndComposer = isShowingTimedTags
                    )
                    player.replaceMediaItem(currentWindowIndex, standardItem)
                    Log.i(TAG, "Pushed enriched metadata update (Final Switch to standard display).")
                }
            }
        }
    }

    private fun extractLocalTagsOnTheFly(metadata: com.dd3boh.outertune.models.MediaMetadata) {
        Log.i(TAG, "on-the-fly tag extraction START: ${metadata.title}, currentComment=${metadata.commentTag != null}" )
        // Check path existence first. If we have a local path, it's a local file regardless of the flag.
        val path = metadata.localPath ?: return
        Log.i(TAG, "on-the-fly tag extraction 0" )
        if (metadata.commentTag != null) return

        Log.i(TAG, "on-the-fly tag extraction 1" )

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Get the chosen scanner. Default to MEDIASTORE if null (uninitialized).
                // Suspending call directly on the Flow is safer than runBlocking here.
                val currentScanner = dataStore.data.first()[ScannerImplKey] ?: ScannerImpl.MEDIASTORE.name

                Log.i(TAG, "on-the-fly tag extraction 2 - Scanner is $currentScanner" )

                if (currentScanner != ScannerImpl.MEDIASTORE.name){

                    Log.i(TAG, "on-the-fly tag extraction 3 return. Skipping (Not MediaStore)" )

                    return@launch
                }

                    Log.i(TAG, "on-the-fly tag extraction 4" )

                    val file = File(path)
                    if (!file.exists()) {
                        Log.e(TAG, "on-the-fly tag extraction 5: File not found at $path")
                        return@launch
                    }


                    Log.i(TAG, "on-the-fly tag extraction 6" )

                    android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        Log.i(TAG, "on-the-fly tag extraction 7: File opened, calling TagLib...")
                        val taglibMetadata = com.kyant.taglib.TagLib.getMetadata(fd.dup().detachFd(), false) ?: return@launch
                        // Default to empty string if not found to mark as "checked" in DB
                        var extractedComment: String = ""
                        var extractedComposer: String = ""

                        taglibMetadata.propertyMap.forEach { (key, value) ->
                            when (key.uppercase()) {
                                "COMMENT" -> extractedComment = value.firstOrNull() ?: ""
                                "COMPOSER" -> extractedComposer = value.firstOrNull() ?: ""
                            }
                        }

                        Log.i(TAG, "on-the-fly tag extraction for Comments: $extractedComment" )
                        Log.i(TAG, "on-the-fly tag extraction for Composer: $extractedComposer" )

                        // Only update if we found actual data
                        if (extractedComment.isNotEmpty() || extractedComposer.isNotEmpty()) {
                            Log.i(TAG, "EXTRACTION SUCCESS: Comment found: '$extractedComment' | Composer found: '$extractedComposer'")

                            // Update DB
                            val songWithArtists = database.song(metadata.id).firstOrNull() ?: return@use
                            database.update(songWithArtists.song.copy(
                                commentTag = extractedComment,
                                composer = extractedComposer
                            ))

                            // Update player session if this is still the current song
                            withContext(Dispatchers.Main) {
                                val currentItem = player.currentMediaItem
                                if (currentItem?.mediaId == metadata.id) {
                                    // Fetch latest metadata from item to avoid overwriting other updates
                                    val currentMetadata = currentItem.localConfiguration?.tag as? com.dd3boh.outertune.models.MediaMetadata ?: metadata
                                    val updatedMetadata = currentMetadata.copy(
                                        commentTag = extractedComment,
                                        composer = extractedComposer
                                    )
                                    updateCurrentMediaMetadata(updatedMetadata)
                                    Log.i(TAG, "SESSION UPDATED: Tags pushed to UI for '${metadata.title}'")
                                }
                            }
                        } else {

                            Log.i(TAG, "on-the-fly tag extraction 8" )

                            // Mark as checked in DB with empty strings even if nothing found
                            val songWithArtists = database.song(metadata.id).firstOrNull() ?: return@use
                            database.update(songWithArtists.song.copy(commentTag = "", composer = songWithArtists.song.composer ?: ""))
                            Log.i(TAG, "on-the-fly tag extraction: No tags found in file, marked as checked.")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "on-the-fly tag extraction failed for ${metadata.title}", e)
            }
        }

        Log.i(TAG, "on-the-fly tag extraction 9" )

    }

    private fun startTimedTagDisplay(capturedMetadata: com.dd3boh.outertune.models.MediaMetadata) {
        timedTagJob?.cancel()
        isShowingTimedTags = false
        if (!capturedMetadata.isLocal) return

        timedTagJob = scope.launch {
            while (isActive) {
                val currentMediaItem = player.currentMediaItem ?: break
                if (currentMediaItem.mediaId != capturedMetadata.id) break

                val pos = player.currentPosition
                val shouldShow = pos in 90000..120000

                if (shouldShow != isShowingTimedTags) {
                    // CRITICAL FIX: Fetch the LATEST metadata from the player item,
                    // which contains the newly extracted commentTag.
                    val latestMetadata = currentMediaItem.localConfiguration?.tag as? com.dd3boh.outertune.models.MediaMetadata
                    if (latestMetadata != null) {
                        updateCurrentMediaMetadata(latestMetadata, showTags = shouldShow)
                    }
                }
                delay(1000)
            }
        }
    }

// Queue

    /**
     * Play a queue.
     *
     * @param queue Queue to play.
     * @param playWhenReady
     * @param shouldResume Set to true for the player should resume playing at the current song's last save position or
     * false to start from the beginning.
     * @param replace Replace media items instead of the underlying logic
     * @param title Title override for the queue. If this value us unspecified, this method takes the value from queue.
     * If both are unspecified, the title will default to "Queue".
     */
    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
        shouldResume: Boolean = false,
        replace: Boolean = false,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        // --- Check if the app was idle for over 4 hours, to reset the session, preventing playback errors ---
        val lastActive = runBlocking {
            dataStore.get(
                androidx.datastore.preferences.core.longPreferencesKey(LAST_ACTIVE_TIME_KEY), 0L)
        }
        if (lastActive != 0L && (System.currentTimeMillis() - lastActive) > IDLE_RESET_THRESHOLD_MS) {
            Log.w(TAG, "IDLE WATCHDOG: App was silent for hours. Resetting session before playback.!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            forceYoutubeReset(rotate = false, clearCookies = false, autoPlay = false)
            // Update the timestamp so we don't reset again immediately
            scope.launch {
                dataStore.edit {
                    it[androidx.datastore.preferences.core.longPreferencesKey(LAST_ACTIVE_TIME_KEY)] = System.currentTimeMillis() } }
        }

        // --- UX CHECK: Ensure session is warmed up ---
        if (!isYouTubeReady.value) {
            Toast.makeText(this, "Player not ready, warming up...", Toast.LENGTH_SHORT).show()
            // We don't return here so that the queue still loads,
            // but it will wait at the URL resolution stage.
        }

        this.queuePlaylistId = if (queue is GlobalRadioQueue) "GLOBAL_RADIO" else queue.playlistId
        Log.i(TAG,"playQueue: queuePlaylistId set to = ${this.queuePlaylistId}")


        if (queue is ListQueue && queue.fullSongList != null) {
            currentFullSongList = queue.fullSongList
        } else {
            currentFullSongList = null
        }

        if (isRadio && queue is GlobalRadioQueue) {
            currentContinuableQueue = queue
            this.globalRadioHistory = GlobalRadioHistory()
            queue.setHistory(this.globalRadioHistory!!)
        } else {
            currentContinuableQueue = null
            globalRadioHistory = null
        }

        scope.launch(Dispatchers.Main) {
            if (!qbInit.value) {
                qbInit.first { it }
            }

            try {
                // 1. Get initial items
                val initialStatus = withContext(Dispatchers.IO) { queue.getInitialStatus() }
                val items = initialStatus.items

                if (items.isEmpty()) {
                    Toast.makeText(this@MusicService, getString(R.string.no_songs_found_for_radio), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val startIndex = if (initialStatus.mediaItemIndex > 0) initialStatus.mediaItemIndex else 0

                // --- Update UI FIRST ---
                // Set the queue on the player immediately so the player screen shows up.
                val newQ = queueBoard.addQueue(
                    title = title ?: initialStatus.title ?: getString(R.string.queue),
                    mediaList = items,
                    shuffled = queue.startShuffled,
                    startIndex = startIndex,
                    replace = replace,
                    continuationEndpoint = if (isRadio) queue.playlistId else null
                )
                queueBoard.setCurrQueue(newQ, shouldResume)
                player.prepare()
                player.playWhenReady = playWhenReady
                // --- UI is now active and loading ---

                // --- Trigger background pre-caching for the tracks in the new queue ---
                Log.i(TAG, "playQueue: Trigger background pre-caching for the tracks in the new queue. runProactivePreCache")
                runProactivePreCache()

                // 2. Pre-resolve URL in the background to speed up start
                val firstItem = items.getOrNull(startIndex)
                if (firstItem != null && !firstItem.isLocal) {
                    launch(Dispatchers.IO) {
                        try {
                            val audioQuality by enumPreference(this@MusicService, AudioQualityKey, AudioQuality.AUTO)
                            val playbackData = YTPlayerUtils.playerResponseForPlayback(
                                videoId = firstItem.id,
                                playlistId = if (isRadio) queue.playlistId else null,
                                audioQuality = audioQuality,
                                connectivityManager = connectivityManager
                            ).getOrNull()

                            if (playbackData?.streamUrl != null) {
                                songUrlCache[firstItem.id] = playbackData.streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
                            }
                        } catch (e: Exception) { /* background fail is okay */ }
                    }
                }

            } catch (e: Exception) {
                if (!isNetworkConnected.value) {
                    waitOnNetworkError()
                } else {
                    reportException(e)
                    Toast.makeText(this@MusicService, "plr: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            Log.i(TAG, "playQueue: Queue resolution complete.")
        }
    }


    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        scope.launch {
            if (!qbInit.value) {
                // when enqueuing next when player isn't active, play as a new song
                if (!items.isEmpty()) {
                    playQueue(
                        ListQueue(
                            title = items.first().mediaMetadata.title.toString(),
                            items = items.mapNotNull { it.metadata as? com.dd3boh.outertune.models.MediaMetadata }
                        )
                    )
                }
            } else {
                // enqueue next
                queueBoard.getCurrentQueue()?.let {
                    queueBoard.addSongsToQueue(
                        it,
                        player.currentMediaItemIndex + 1,
                        items.mapNotNull { item -> item.metadata as? com.dd3boh.outertune.models.MediaMetadata }
                    )
                }
            }
        }
    }

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<com.dd3boh.outertune.models.MediaMetadata>) {
        queueBoard.enqueueEnd(items)
    }

    fun triggerShuffle() {
        val oldIndex = player.currentMediaItemIndex
        queueBoard.setCurrQueuePosIndex(oldIndex)
        val currentQueue = queueBoard.getCurrentQueue() ?: return

        // shuffle and update player playlist
        if (!currentQueue.shuffled) {
            queueBoard.shuffleCurrent()
        } else {
            queueBoard.unShuffleCurrent()
        }
        queueBoard.setCurrQueue()

        updateNotification()
    }

    suspend fun initQueue() {
        Log.i(TAG, "+initQueue()")
        // Get user preferences for queue persistence.
        val persistQueue = dataStore.get(PersistentQueueKey, true)
        val maxQueues = dataStore.get(MaxQueuesKey, 19)

        // If persistence is enabled, load the saved queues from the database.
        if (persistQueue) {
            val savedQueues = database.readQueue().toMutableList()
            Log.d(TAG, "Read ${savedQueues.size} queues from database. Validating state...")
            // --- Safety Check: Validate and correct any corrupt queue states before loading ---
            savedQueues.forEach { queue ->
                if (queue.queue.isEmpty()) {
                    if (queue.queuePos != 0) {
                        Log.w(TAG,"CRITICAL: Correcting corrupt empty queue. Index was ${queue.queuePos}, resetting to 0.")
                        queue.queuePos = 0
                    }
                } else if (queue.queuePos < 0 || queue.queuePos >= queue.queue.size) {
                    Log.w(TAG,"CRITICAL: Correcting corrupt queue index. Was ${queue.queuePos}, size ${queue.queue.size}. Resetting to 0.")
                    queue.queuePos = 0
                }
            }
            // Load the validated state into the QueueBoard.
            queueBoard.loadState(savedQueues, maxQueues)
        } else {
            // If persistence is disabled, start with a clean slate.
            queueBoard.loadState(emptyList(), maxQueues)
        }

        // Get the last-played queue, which is now the current one in the QueueBoard.
        val resumptionQueue = queueBoard.getCurrentQueue()
        if (resumptionQueue != null) {

            // =========================================================================
            //  FIX #1: RESTORE THE GLOBAL RADIO CONTEXT
            // =========================================================================
            // The purpose of this block is to correctly identify if the queue being
            // restored from the database is a "Global Radio" session.
            // This is critical for the "never stop" error handling logic in onPlayerError.
            if (resumptionQueue.title?.startsWith("Global Radio") == true) {
                // If it is, we set the service's context variable to the constant "GLOBAL_RADIO".
                this.queuePlaylistId = "GLOBAL_RADIO"
            } else {
                // Otherwise, we use the queue's own unique ID.
                this.queuePlaylistId = resumptionQueue.id.toString()
            }
            // =========================================================================
            //  END OF FIX #1
            // =========================================================================


            // =========================================================================
            //  FIX #2: RESTORE PLAYER STATE & PREVENT UI FLICKER
            // =========================================================================
            // The purpose of this block is to update the ExoPlayer instance itself
            // with the songs from the restored queue.
            // This prepares the player's timeline so the UI can correctly display
            // the last-played song and queue information immediately on app startup.

            withContext(Dispatchers.Main) {
                // This tells the QueueBoard to update ExoPlayer with the media items.
                queueBoard.setCurrQueue(resumptionQueue, shouldResume = true)
                // This prepares the player but does NOT start playback, allowing the UI to reflect the state.
                player.prepare()
            }
            // =========================================================================
            //  END OF FIX #2
            // =========================================================================
        }

        Log.i(TAG, "Queue init complete. Persist queue = $persistQueue. Queues loaded = ${queueBoard.masterQueues.size}")
        // Signal that the queue board is initialized and ready for other parts of the app to use.
        qbInit.value = true
        Log.d(TAG, "-initQueue()")
    }


    fun deInitQueue() {
        Log.i(TAG, "+deInitQueue()")
        val pos = player.currentPosition
        queueBoard.shutdown()
        if (dataStore.get(PersistentQueueKey, true)) {
            runBlocking(Dispatchers.IO) {
                saveQueueToDisk(pos)
            }
        }
        // do not replace the object. Can lead to entire queue being deleted even though it is supposed to be saved already
        qbInit.value = false
        Log.i(TAG, "-deInitQueue()")
    }

    suspend fun saveQueueToDisk(currentPosition: Long) {
        val data = queueBoard.getAllQueues()
        if (data.isNotEmpty()) {
            data.last().lastSongPos = currentPosition

            // --- THE DEFINITIVE FIX: VALIDATE BEFORE SAVING ---
            Log.d(TAG, "Validating ${data.size} queues before saving to disk...")
            var corruptionFound = false
            data.forEach { queue ->
                // This is the safety check that was missing from the save path.
                if (queue.queuePos < 0 || queue.queuePos >= queue.queue.size) {
                    Log.w(
                        TAG, "CRITICAL: Correcting corrupt queue state before save. " +
                                "Queue: '${queue.title}', Invalid Index: ${queue.queuePos}, " +
                                "Size: ${queue.queue.size}. Resetting to 0."
                    )
                    // If the index is invalid, safely reset it to 0 before saving.
                    queue.queuePos = 0
                    corruptionFound = true
                }
            }
            if (!corruptionFound) {
                Log.d(TAG, "Validation passed. All queue states are consistent.")
            }
            // --- END FIX ---

            // Now, we are guaranteed to be saving a clean, valid state to the database.
            database.updateAllQueues(data)
        }
    }


// Audio playback

    private fun openAudioEffectSession() {
        if (isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = true
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            }
        )
    }

    private fun closeAudioEffectSession() {
        if (!isAudioEffectSessionOpened) return
        isAudioEffectSessionOpened = false
        sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
    }


    private fun createCacheDataSource(upstreamFactory: DataSource.Factory): CacheDataSource.Factory {
        return CacheDataSource.Factory()
            .setCache(downloadCache)
            // No DataSink here: We do not write to the permanent download cache during playback
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(
                CacheDataSource.Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setCacheWriteDataSinkFactory(
                        HybridCacheDataSinkFactory(playerCache) { dataSpec ->
                            val isLocal = queueBoard.getCurrentQueue()
                                ?.findSong(dataSpec.key ?: "")?.isLocal == true
                            Log.d(TAG, "SONG CACHE: ${!isLocal}")
                            !isLocal
                        }
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            )
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // Create a dedicated thread that never shares with the rest of the app

    private val resolutionThread = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun createDataSourceFactory(): DataSource.Factory {
        Log.i(TAG, "---------------BEGIN createDataSourceFactory----------------------")
        // 1. Create the Network Factory
        val httpDataSourceFactory = OkHttpDataSource.Factory(YTPlayerUtils.httpClient)

        // 2. Wrap it in DefaultDataSource (The Router).
        // THIS IS THE FIX: It handles file:// URIs correctly so they don't reach OkHttp.
        val defaultFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        // 3. Use the Router as the upstream for the Cache
        val baseFactory = createCacheDataSource(defaultFactory)

        return ResolvingDataSource.Factory(baseFactory) { dataSpec ->
            // 1. Safely extract mediaId
            val mediaId = dataSpec.key?: dataSpec.uri.getQueryParameter("v")
            ?: dataSpec.uri.lastPathSegment
            ?: dataSpec.uri.toString().removePrefix("content://").removePrefix("/")

            Log.i(TAG, "---- ResolvingDataSource: Processing $mediaId -----")

            // 2. Check for local file (SD card/Internal storage)
            val songFromDb = runBlocking { database.song(mediaId).firstOrNull() }
            if (songFromDb?.song?.localPath != null && songFromDb.song.isLocal) {
                val file = File(songFromDb.song.localPath!!)
                if (file.exists()) {
                    return@Factory dataSpec.buildUpon()
                        .setUri(file.toUri())
                        .setKey(mediaId)
                        .build()
                }
            }

            // 3. THE CACHE LOGIC: Determine if we can play without network
            val offlineUri = downloadUtil.localMgr.getFilePathIfExists(mediaId)

            // Check FULL cache status using metadata (Accurate check)
            val metadata = playerCache.getContentMetadata(mediaId)
            val length = metadata.get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, C.LENGTH_UNSET.toLong())
            val isFullyInPlayerCache = length != C.LENGTH_UNSET.toLong() && playerCache.isCached(mediaId, 0L, length)

            // Partial checks
            val isPartiallyInPlayerCache = try { playerCache.isCached(mediaId, 0L, 1L) } catch (e: Exception) { false }
            val isPhysicallyInDownload = try { downloadCache.isCached(mediaId, 0L, 1L) } catch (e: Exception) { false }
            val hasAnyLocalData = offlineUri != null || isPhysicallyInDownload || isPartiallyInPlayerCache

            // --- THE HYBRID INTERCEPT ---
            // A. If song is 100% cached, use it immediately. This bypasses network entirely.
            // B. If app is OFFLINE and we have partial data, use it as best-effort fallback.
            if (isFullyInPlayerCache || (!isNetworkConnected.value && hasAnyLocalData)) {
                val placeholderUri = offlineUri ?: android.net.Uri.parse("https://music.youtube.com/watch?v=$mediaId")
                Log.i(TAG, "CACHE INTERCEPT: Using disk for $mediaId. Full=$isFullyInPlayerCache, Offline=${!isNetworkConnected.value}")

                return@Factory androidx.media3.datasource.DataSpec.Builder()
                    .setUri(placeholderUri)
                    .setKey(mediaId)
                    .setPosition(dataSpec.position)
                    .setLength(dataSpec.length)
                    .build()
            }

            // 4. Gatekeeper: Only if NOT fully in cache, check current network status
            if (!isNetworkConnected.value) {
                Log.w(TAG, "Network disconnected and song $mediaId not fully in cache. Failing resolution.")
                throw IOException("Network disconnected")
            }

            // 5. Memory Cache Path
            songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                offloadScope.launch { recoverSong(mediaId) }
                return@Factory dataSpec.buildUpon()
                    .setUri(it.first.toUri())
                    .setKey(mediaId)
                    .build()
            }

            // 6. Online Path: Resolve fresh URL
            Log.i(TAG, "ResolvingDataSource: Fetching URL for $mediaId")
            return@Factory runBlocking(Dispatchers.IO) {
                try {
                    withTimeout(7000) {
                        val audioQuality = dataStore.data.map {
                            it[AudioQualityKey]?.let { q -> com.dd3boh.outertune.constants.AudioQuality.valueOf(q) }
                                ?: com.dd3boh.outertune.constants.AudioQuality.AUTO
                        }.first()

                        val playbackData = YTPlayerUtils.playerResponseForPlayback(
                            mediaId,
                            null,
                            audioQuality,
                            connectivityManager).getOrThrow()

                        val streamUrl = playbackData.streamUrl
                        songUrlCache[mediaId] = streamUrl to System.currentTimeMillis() + (playbackData.streamExpiresInSeconds * 1000L)
                        offloadScope.launch { recoverSong(mediaId, playbackData) }

                        val headers = YTPlayerUtils.getPlaybackHeaders(playbackData.userAgent, playbackData.visitorData)

                        androidx.media3.datasource.DataSpec.Builder()
                            .setUri(streamUrl.toUri())
                            .setHttpRequestHeaders(headers)
                            .setKey(mediaId)
                            .build()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ResolvingDataSource: Failed for $mediaId", e)

                    // --- THE LAST RESORT FALLBACK ---
                    // If network resolution failed but we have SOME data, use placeholder.
                    // This saves the track if the network flag was wrong or connection reset.
                    if (hasAnyLocalData) {
                        Log.i(TAG, "ResolvingDataSource: Fallback to partial cache for $mediaId")
                        val placeholderUri = offlineUri ?: android.net.Uri.parse("https://music.youtube.com/watch?v=$mediaId")
                        return@runBlocking androidx.media3.datasource.DataSpec.Builder()
                            .setUri(placeholderUri)
                            .setKey(mediaId)
                            .setPosition(dataSpec.position)
                            .setLength(dataSpec.length)
                            .build()
                    }
                    throw IOException("Resolution failed or timed out", e)
                }
            }
        }
    }


    private fun createRenderersFactory(gaplessOffloadAllowed: Boolean): DefaultRenderersFactory {
        if (ENABLE_FFMETADATAEX) {
            return object : NextRenderersFactory(this@MusicService) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            if (!gaplessOffloadAllowed) OtOffloadSupportProvider(
                                context
                            ) else DefaultAudioOffloadSupportProvider(context)
                        )
                        .build()
                }
            }
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(audioDecoder)
        } else {
            return object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    pcmEncodingRestrictionLifted: Boolean,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? {
                    return DefaultAudioSink.Builder(this@MusicService)
                        .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessorChain(
                            DefaultAudioSink.DefaultAudioProcessorChain(
                                emptyArray(),
                                SilenceSkippingAudioProcessor(),
                                SonicAudioProcessor()
                            )
                        )
                        .setAudioOffloadSupportProvider(
                            if (!gaplessOffloadAllowed) OtOffloadSupportProvider(
                                context
                            ) else DefaultAudioOffloadSupportProvider(context)
                        )
                        .build()
                }
            }
        }
    }


// Misc

    fun updateNotification() {
        mediaSession.setCustomLayout(
            listOf(
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(getString(if (queueBoard.getCurrentQueue()?.shuffled == true) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setSessionCommand(CommandToggleShuffle)
                    .setCustomIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle_off)
                    .build(),
                CommandButton.Builder(ICON_UNDEFINED)
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            }
                        )
                    )
                    .setCustomIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat_off
                            REPEAT_MODE_ONE -> R.drawable.repeat_one
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        }
                    )
                    .setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton.Builder(if (currentSong.value?.song?.liked == true) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                    .setDisplayName(getString(if (currentSong.value?.song?.liked == true) R.string.action_remove_like else R.string.action_like))
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_RADIO)
                    .setDisplayName(getString(R.string.start_radio))
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build()
            )
        )
    }

    fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
        Toast.makeText(this@MusicService, getString(R.string.wait_to_reconnect), Toast.LENGTH_LONG)
            .show()
    }

    fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_PLAYER_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()

            Toast.makeText(
                this@MusicService,
                getString(R.string.err_play_next_on_error),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        player.pause()
        Toast.makeText(
            this@MusicService,
            getString(R.string.err_stop_on_too_many_errors),
            Toast.LENGTH_LONG
        ).show()
        consecutivePlaybackErr = 0
    }

    fun stopOnError() {
        player.pause()
        Toast.makeText(this@MusicService, getString(R.string.err_stop_on_error), Toast.LENGTH_LONG)
            .show()
    }


    /**
     * @param rotate If true, rotates to next official version.
     * @param targetVersion If provided, forces this specific version (ignores rotation).
     * @param clearCookies If true, logs the user out.
     */
    fun forceYoutubeReset(
            rotate: Boolean = true,
            targetVersion: String? = null,
            clearCookies: Boolean = true,
            autoPlay: Boolean = false
    ) {
            if (isManualResetting) return
            isManualResetting = true

        // Capture the current ID on the Main thread before switching to IO
        val currentId = player.currentMediaItem?.mediaId

            scope.launch(Dispatchers.IO) {
                    // 1. OFFICIAL VERSION ROTATION   as of 2026.03.12
    //                const val VR_CLIENT_LATEST_VERSION = "1.72.15"
    //                const val VR_CLIENT_FALLBACK_VERSION = "1.72.14"
    //                const val VR_CLIENT_ALT1_VERSION = "1.70.10"
    //                const val VR_CLIENT_ALT2_VERSION = "1.61.48"
    //                const val WEB_VER_KEY = "1.20260310.01.00"
    //                const val ANDROID_VER_KEY = "21.05.34"
    //                const val IOS_VER_KEY = "19.48.3"

                    try {
                        Log.w(TAG, "YOUTUBE RESET: Full Wipe !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                        Log.i(TAG, "RESET_LOG: Starting forceYoutubeReset(rotate=$rotate)")
                        Log.i(TAG, "RESET_LOG: Current VR Version before: ${YouTubeClient.currentVrVersion}")
                        Log.i(TAG, "RESET_LOG: Current VisitorData: ${YouTube.visitorData}")

                        // 1. Version Handling
                        val vrVerKey = VrVerKey
                        if (targetVersion != null) {
                            Log.i(TAG, "RESET_LOG: Forcing specific version: $targetVersion")
                            dataStore.edit { it[vrVerKey] = targetVersion }
                            YouTubeClient.currentVrVersion = targetVersion
                        } else if (rotate) {
                            val officialVersions = listOf(
                                VR_CLIENT_FALLBACK_VERSION,
                                VR_CLIENT_LATEST_VERSION,
                                VR_CLIENT_ALT1_VERSION,
                                VR_CLIENT_ALT2_VERSION
                            )
                            //val vrVerKey = stringPreferencesKey("vr_version_override")
                            val currentActive = YouTubeClient.currentVrVersion
                            val currentIndex = officialVersions.indexOf(currentActive)

                            val rotatedVersion = if (currentIndex == -1 || currentIndex == officialVersions.lastIndex) {
                                officialVersions[0]
                            } else {
                                officialVersions[currentIndex + 1]
                            }

                            dataStore.edit { it[VrVerKey] = rotatedVersion }
                            YouTubeClient.currentVrVersion = rotatedVersion
                            Log.i(TAG, "Identity Rotated: $currentActive -> $rotatedVersion")
                        }else {
                            Log.i(TAG, "RESET_LOG: Keeping current version: ${YouTubeClient.currentVrVersion}")
                        }

                        // 2. Cookie Handling
                        // ONLY clear cookies if explicitly requested.
                        // This prevents logging out during a post-login socket refresh.
                        if (clearCookies) {
                            withContext(Dispatchers.Main) {
                                Log.i(TAG, "RESET_LOG: Wiping WebView Cookies (This will logout the user)")
                                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                                android.webkit.WebStorage.getInstance().deleteAllData()
                            }
                        }else {
                            Log.i(TAG, "RESET_LOG: Skipping cookie wipe (User will remain logged in)")
                        }

                        // 3. Cleanup & Restart
                        Log.i(TAG, "RESET_LOG: Evicting Connection Pool and clearing URL cache")
                        // Always clear these
                        YouTube.visitorData = null
                        songUrlCache.clear()
                        YTPlayerUtils.httpClient.connectionPool.evictAll()

                        delay(1500)

                        // Now we need to force the app to restart
                        // Pass the captured currentId to the restart function
                        triggerAppRestart(autoPlay = autoPlay, manualFailedId = currentId)

                } catch (e: Exception) {
                    Log.e(TAG, "Manual reset failed !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", e)
                } finally {
                    isManualResetting = false
                }
            }
    }

    /**
     * Performs a 2-stage "Launch-then-Kill" restart.
     * This pattern bypasses Android background restrictions by claiming foreground focus
     * BEFORE the process is terminated.
     * @param manualFailedId If provided, uses this ID as the failed track to skip after restart.
     */
    fun triggerAppRestart(autoPlay: Boolean = false, manualFailedId: String? = null) {
        // Use the passed ID or try to get it if we are on the main thread
        val currentId = manualFailedId ?: try { player.currentMediaItem?.mediaId } catch (e: Exception) { null }

        val startTs = android.os.SystemClock.elapsedRealtime()
        val pid = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        Log.i(TAG, "triggerAppRestart START pid=$pid uid=$uid autoPlay=$autoPlay ts=$startTs !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

        // Build a robust restart Intent using the launch component
        val pmIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (pmIntent == null) {
            Log.w(TAG, "triggerAppRestart: no launch intent for package, aborting restart !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            return
        }
        val component = pmIntent.component
        val restartIntent = Intent.makeRestartActivityTask(component).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("expandPlayer", autoPlay)
        }

        // Persist the AutoPlay flag synchronously so it survives the process exit
        try {
            runBlocking {
                dataStore.edit { settings ->
                    settings[AutoPlayRestartKey] = autoPlay
                    if (currentId != null) {
                        settings[androidx.datastore.preferences.core.stringPreferencesKey("failed_media_id")] = currentId
                    }
                }
            }
            Log.i(TAG, "triggerAppRestart: persisted AutoPlay=$autoPlay and failedId=$currentId")
        } catch (e: Exception) {
            Log.w(TAG, "triggerAppRestart: failed persisting state", e)
        }

        // --- NEW: Hand off restart attempts to a short-lived foreground helper service ---
        // Start ForegroundRestartService which will promote itself and perform the robust start attempts.
        // If starting the helper service fails, we fall back to inline attempts below.
        val svcIntent = Intent(this, ForegroundRestartService::class.java).apply {
            putExtra("autoPlay", autoPlay)
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
            Log.i(TAG, "triggerAppRestart: started ForegroundRestartService to perform restart attempts")
            // Hand off restart logic to the helper service; stop inline attempts here.
            return
        } catch (e: Exception) {
            Log.w(TAG, "triggerAppRestart: failed to start ForegroundRestartService, falling back to inline attempts", e)
        }


        // Prepare PendingIntent and AlarmManager for fallback
        val restartPI = PendingIntent.getActivity(
            this,
            0,
            restartIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager

        var attempt = 0
        var successMethod: String? = null

        // Inline promotion to foreground removed: restarted attempts are delegated to
        // ForegroundRestartService (started earlier). Keep inline fallback attempts
        // (startActivity / PendingIntent / AlarmManager) below as a last resort.

        // 1) Attempt: in-process startActivity (best chance to claim foreground)
        attempt++
        val t1 = android.os.SystemClock.elapsedRealtime()
        try {
            startActivity(restartIntent)
            successMethod = "startActivity"
            val t2 = android.os.SystemClock.elapsedRealtime()
            Log.i(TAG, "triggerAppRestart: attempt=$attempt method=startActivity SUCCESS elapsed=${t2 - t1}ms")
        } catch (e: Exception) {
            val t2 = android.os.SystemClock.elapsedRealtime()
            Log.w(TAG, "triggerAppRestart: attempt=$attempt method=startActivity FAILED elapsed=${t2 - t1}ms ex=${e::class.java.simpleName}: ${e.message}")
            Log.w(TAG, e.stackTraceToString())
        }

        // 2) Attempt: PendingIntent.send retries with backoff (200ms, 500ms, 1000ms)
        val backoffs = longArrayOf(200, 500, 1000)
        for (b in backoffs) {
            if (successMethod != null) break
            attempt++
            try {
                if (b > 0) Thread.sleep(b)
            } catch (_: InterruptedException) {}

            val ta = android.os.SystemClock.elapsedRealtime()
            try {
                restartPI.send()
                successMethod = "pendingIntent.send"
                val tb = android.os.SystemClock.elapsedRealtime()
                Log.i(TAG, "triggerAppRestart: attempt=$attempt method=pendingIntent.send SUCCESS elapsed=${tb - ta}ms backoff=${b}ms")
                break
            } catch (e: Exception) {
                val tb = android.os.SystemClock.elapsedRealtime()
                Log.w(TAG, "triggerAppRestart: attempt=$attempt method=pendingIntent.send FAILED elapsed=${tb - ta}ms backoff=${b}ms ex=${e::class.java.simpleName}: ${e.message}")
                Log.w(TAG, e.stackTraceToString())
            }
        }

        // 3) AlarmManager fallback: schedule an exact alarm for +5000ms
        try {
            if (alarmManager != null) {
                val triggerAt = System.currentTimeMillis() + 5000
                try {
                    alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, restartPI)
                    Log.i(TAG, "triggerAppRestart: alarm fallback scheduled at ${triggerAt} (+5000ms)")
                } catch (e: NoSuchMethodError) {
                    // Older devices: fallback to set
                    alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, restartPI)
                    Log.i(TAG, "triggerAppRestart: alarm fallback (set) scheduled at ${triggerAt} (+5000ms)")
                }
            } else {
                Log.w(TAG, "triggerAppRestart: AlarmManager not available; skipping alarm fallback")
            }
        } catch (e: Exception) {
            Log.w(TAG, "triggerAppRestart: scheduling alarm fallback failed", e)
        }

        val totalElapsed = android.os.SystemClock.elapsedRealtime() - startTs
        Log.i(TAG, "triggerAppRestart: attempts completed. successMethod=$successMethod attempts=$attempt totalElapsed=${totalElapsed}ms")

        // Pause briefly to allow any in-process start to complete before we exit
        try { Thread.sleep(1500) } catch (_: InterruptedException) {}

        Log.w(TAG, "triggerAppRestart: exiting process now to complete restart handoff.")
        exitProcess(0)
    }




    private fun findNextLocalSongIndex(): Int {
        val currentPlaylistItems = player.currentTimeline.let { timeline ->
            if (timeline.isEmpty) return -1
            (0 until timeline.windowCount).map { timeline.getWindow(it, Timeline.Window()).mediaItem }
        }
        for (i in (player.currentMediaItemIndex + 1) until currentPlaylistItems.size) {
            if (currentPlaylistItems[i].mediaMetadata?.extras?.getBoolean("isLocal", false) == true) {
                return i
            }
        }
        return -1 // Not found
    }

    private fun findNextOfflineSongIndex(): Int {
        val currentIndex = player.currentMediaItemIndex
        val count = player.mediaItemCount

        for (i in (currentIndex + 1) until count) {
            val item = player.getMediaItemAt(i)

            // Check metadata extras first
            val isLocalExtra = item.mediaMetadata.extras?.getBoolean("isLocal", false) ?: false
            if (isLocalExtra) {
                return i
            }

            // Fallback: Check the database directly for download status
            val mediaId = item.mediaId
            val isOfflineAvailable = runBlocking(Dispatchers.IO) {
                val song = database.song(mediaId).firstOrNull()
                song?.song?.isLocal == true || song?.song?.dateDownload != null
            }

            if (isOfflineAvailable) {
                return i
            }
        }
        return -1
    }



    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        super.onPlayWhenReadyChanged(playWhenReady, reason)
        // If the user manually pauses (playWhenReady becomes false),
        // we MUST stop the background retry loop.
        if (!playWhenReady) {
            if (globalRadioRetryJob?.isActive == true) {
                Log.d(TAG, "User manually paused. Cancelling Global Radio retry loop.")
                globalRadioRetryJob?.cancel()
                firstFailedSongIndex = -1
            }
        }
    }

    // Player overrides

    /**
     * Performs a robust check to see if the network is strong enough for data transfer
     * by attempting an HTTP request to a lightweight connectivity endpoint.
     */
//    private suspend fun isInternetActuallyAvailable(): Boolean = withContext(Dispatchers.IO) {
//        try {// Using generate_204 checks DNS, TCP, SSL and actual HTTP data flow.
//            // A slow EDGE connection will likely time out here, returning false.
//            Log.i(TAG, "isInternetActuallyAvailable? starting checks...")
//            val url = java.net.URL("https://connectivitycheck.gstatic.com/generate_204")
//            val connection = url.openConnection() as java.net.HttpURLConnection
//            connection.connectTimeout = 4500 // 4.5s timeout: kills EDGE/GPRS, lenient for 3G
//            connection.readTimeout = 4500
//            connection.instanceFollowRedirects = false
//            connection.setRequestProperty("Connection", "close")
//            connection.connect()
//            val responseCode = connection.responseCode
//            Log.i(TAG, "isInternetActuallyAvailable? responseCode = $responseCode")
//            connection.disconnect()
//            responseCode == 204
//        } catch (e: Exception) {
//            false
//        }
//    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        val isGlobalRadio = queuePlaylistId?.startsWith("GLOBAL_RADIO") == true
        Log.e(TAG, "onPlayerError: ${error.errorCodeName} (${error.errorCode}): ${error.message}")

        // 1. Identify Network Errors
        val isNetworkError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                !isNetworkConnected.value

        if (isNetworkError) {
            if (isGlobalRadio) {
                if (firstNetworkErrorTime == 0L) firstNetworkErrorTime = System.currentTimeMillis()
                val elapsed = System.currentTimeMillis() - firstNetworkErrorTime
                scope.launch {
                    val internetIsBack = isInternetActuallyAvailable()

                    if (internetIsBack) {
                        // FIX: If internet is WORKING (HTTP check passed) but resolution failed,
                        // it is a "Fake" network error caused by a broken YouTube session.
                        // We must reset the app to heal the session and resume playback.
                        withContext(Dispatchers.Main) {
                            //  Ensure reset happens on the Main thread to avoid IllegalStateException
                            Log.i(TAG, "GLOBAL RADIO: Network failure detected, but Internet is WORKING (HTTP Success)! Forcing reset.")
                            forceYoutubeReset(rotate = true, clearCookies = false, autoPlay = true)
                        }
                    } else if (elapsed < 20000 && networkRetryCount < 2) {
                        networkRetryCount++
                        Log.w(TAG, "GLOBAL RADIO: Truly offline. Retry $networkRetryCount of 2. Waiting 2s...!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

                        delay(2000) // 2s grace period

                        // Clear session even on offline retries to ensure no stale data
                        withContext(Dispatchers.IO) {
                            YouTube.visitorData = null
                            YTPlayerUtils.httpClient.connectionPool.evictAll()
                            songUrlCache.clear()
                        }

                        player.prepare()
                        player.play()
                    } else {
                        // All retries failed or timeout reached - proceed to local fallback
                        Log.e(TAG, "GLOBAL RADIO: Recovery failed after ${elapsed}ms. Initiating offline recovery.!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

                        val nextLocalIndex = findNextOfflineSongIndex()
                        if (nextLocalIndex != -1) {
                            Log.i(TAG, "GLOBAL RADIO: Skipping to next available offline song at index $nextLocalIndex.!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                            player.seekTo(nextLocalIndex, C.TIME_UNSET)
                            player.prepare()
                            player.play()
                        } else {
                            // Current queue has no local songs. Check if the library has ANY local songs before replacing the queue.
                            val hasLocalMedia = withContext(Dispatchers.IO) {
                                database.getRandomLocalSongEntities(1).isNotEmpty() ||
                                        database.getRandomDownloadedSongEntities(1).isNotEmpty()
                            }

                            if (hasLocalMedia) {
                                Log.w(TAG, "GLOBAL RADIO: No offline songs in current queue. Forcing local-only batch.!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                                val songsCount = dataStore.get(com.dd3boh.outertune.constants.GlobalRadioLocalAndLikedSongsCountKey, 10)
                                val localOnlyQueue = GlobalRadioQueue(
                                    context = this@MusicService,
                                    db = database,
                                    localAndLikedSongLimit = songsCount,
                                    albumAndArtistLimit = 0, // Disable online components
                                    songsPerArtistLimit = 0
                                )
                                playQueue(localOnlyQueue, replace = true, isRadio = true, title = "Global Radio (Offline Mode)")
                            } else {
                                // No local music at all - we have no choice but to wait for the network
                                Log.e(TAG, "GLOBAL RADIO: No local media found in library. Waiting for network connection...!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                                waitOnNetworkError()
                            }
                        }
                    }
                }
                return
            }
            else {
                waitOnNetworkError()
                return
            }
        }


        // --- 2. Identify "Hard" Failures (Restricted / Resolution Failed / Timeout) ---
        // If the URL couldn't be fetched or it timed out after 8 seconds
        val isPermanentFail = error.message?.contains("Resolution failed") == true ||
                error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT

        if (isPermanentFail) {
            Log.w(TAG, "Permanent failure detected (likely restriction). isGlobalRadio=$isGlobalRadio !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
            if (isGlobalRadio) {
                Log.i(TAG, "Global Radio: Skipping restricted/broken track immediately.")
                scope.launch {
                    delay(500) // Brief delay to prevent UI flickering
                    val internetIsBack = isInternetActuallyAvailable()

                    withContext(Dispatchers.Main) {
                        //  Ensure reset happens on the Main thread
                        if (internetIsBack) {
                            Log.i(TAG,"GLOBAL RADIO: Resolution failed but Internet is UP. Forcing session reset.")
                            forceYoutubeReset(rotate = true, clearCookies = false, autoPlay = true)
                        } else {
                            Log.i(TAG, "GLOBAL RADIO: Truly offline. Skipping track.")
                            player.seekToNextMediaItem()
                            player.prepare()
                            player.play()
                        }
                    }
                }
                return
            } else {
                // If not Global Radio, use the user's preference (skip or stop)
                if (dataStore.get(SkipOnErrorKey, false)) {
                    skipOnError()
                } else {
                    stopOnError()
                }
                return
            }
        }

        // --- 3. Source Error / Expired URL Handler (Standard Online Songs) ---
        // We keep this for normal songs that just had a timeout or expired link.
        val isSourceErrorOnOnlineSong = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
                player.currentMediaItem?.mediaId != null

        if (isSourceErrorOnOnlineSong && consecutiveUrlErrors < 3) { // Lowered to 3 to fail faster
            consecutiveUrlErrors++
            Log.w(TAG, "Temporary source error. Retrying... Attempt #$consecutiveUrlErrors !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

            // --- NEW: Automatic Session Reset ---
            // If the first simple retry (cache clear) didn't work, we perform a
            // "Nuclear Reset" of the session identity on attempt 2 and 3.
            if (consecutiveUrlErrors >= 2) {
                Log.w(TAG, "Stuck song detected. Automatically resetting YouTube session visitorData. !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")
                YouTube.visitorData = null
            }

            val failedMediaId = player.currentMediaItem?.mediaId
            songUrlCache.clear()

            scope.launch {
                delay(2000)
                failedMediaId?.let { playerCache.removeResource(it) }
                player.prepare()
                player.play()
            }
            return
        }

        // --- 4. "Never Stop" Global Radio Fallback (General Errors) ---
        if (isGlobalRadio) {
            if (firstFailedSongIndex == -1) {
                firstFailedSongIndex = player.currentMediaItemIndex
            }

            val nextLocalIndex = findNextOfflineSongIndex()
            if (nextLocalIndex != -1) {
                Log.i(TAG, "Global Radio: Using local fallback.")
                player.seekTo(nextLocalIndex, C.TIME_UNSET)
                player.prepare()
                player.play()
                return
            }

            if (player.hasNextMediaItem()) {
                Log.i(TAG, "Global Radio: Error, skipping to next.")
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                Log.w(TAG, "Global Radio: End of queue error. Starting retry loop.")
                startGlobalRadioRetryLoop()
            }
            return
        }

        // --- 5. Default Handler ---
        if (dataStore.get(SkipOnErrorKey, false)) {
            skipOnError()
        } else {
            stopOnError()
        }
    }

    /**
     * Periodic retry loop for Global Radio when the network is lost.
     * It attempts to resume from the FIRST song that failed.
     */
    private fun startGlobalRadioRetryLoop() {
        globalRadioRetryJob?.cancel()
        val currentId = queuePlaylistId // Ensure we only retry the same queue context

        globalRadioRetryJob = scope.launch {
            // Requirement: Keep the "Pause" icon visible (active state)
            player.playWhenReady = true

            while (isActive && queuePlaylistId == currentId && player.playWhenReady) {
                delay(10000) // Wait 10 seconds

                // Use the saved firstFailedSongIndex to go back in time
                val resumeIndex = if (firstFailedSongIndex != -1) firstFailedSongIndex else player.currentMediaItemIndex
                Log.d(TAG, "Global Radio: 10s elapsed. Retrying from index $resumeIndex...!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")

                withContext(Dispatchers.Main) {
                    // Try to go back to the point where music stopped
                    if (resumeIndex < player.mediaItemCount) {
                        player.seekTo(resumeIndex, 0)
                    }
                    player.prepare()
                    player.play()
                }

                // Wait a few seconds to see if it starts playing or errors again
                delay(5000)

                // If playback successfully started or is ready, we have recovered!
                if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                    Log.d(TAG, "Global Radio: Recovery successful!  :)))))))))))))))))))))))))")
                    break // Exit the retry loop
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            // Log when audio actually starts coming out (after buffering/loading)
            Log.i(TAG, "----------------------\nLIFECYCLE: Audio playback is now ACTIVE for: '${player.currentMediaItem?.mediaMetadata?.title} by ${player.currentMediaItem?.mediaMetadata?.artist} ' \n----------------------")
            firstNetworkErrorTime = 0L
            networkRetryCount = 0

            // --- FIX: Only reset markers when music successfully plays ---
            Log.d(TAG, "Playback started. Resetting failure markers.")
            firstFailedSongIndex = -1
            consecutiveUrlErrors = 0
            consecutivePlaybackErr = 0
            networkRetryCount = 0

            // --- FIX: Ensure radio continues if we recovered on the last song ---
            val isGlobalRadio = queuePlaylistId?.startsWith("GLOBAL_RADIO") == true
            if (isGlobalRadio && !player.hasNextMediaItem()) {
                currentContinuableQueue?.let { queue ->
                    if (queue.hasNextPage()) {
                        Log.d(TAG, "Global Radio: Recovered on last song. Triggering load of more items.")
                        scope.launch(Dispatchers.IO) {
                            try {
                                val nextItems = queue.nextPage()
                                if (nextItems.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        queueBoard.enqueueEnd(nextItems)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Global Radio: Failed to load more items during recovery", e)
                            }
                        }
                    }
                }
            }
        } else {
            Log.i(TAG, "LIFECYCLE: Audio playback is now PAUSED or STOPPED.")
            val currentTime = System.currentTimeMillis()
            scope.launch {
                dataStore.edit {
                    it[androidx.datastore.preferences.core.longPreferencesKey(LAST_ACTIVE_TIME_KEY)] = currentTime }
            }

            val pos = player.currentPosition
            val q = queueBoard.getCurrentQueue()
            q?.lastSongPos = pos
        }
        super.onIsPlayingChanged(isPlaying)
    }


    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)

        // --- LIFECYCLE LOGS ---
        val transitionReason = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO (Natural end of previous song)"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK (Manual Next/Previous or selection from current queue)"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED (New list loaded or song picked from a different list)"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
            else -> "UNKNOWN ($reason)"
        }
        Log.i(TAG, "=====================================================\nLIFECYCLE: Playback process STARTING for song: '${mediaItem?.mediaMetadata?.title}' | Reason: $transitionReason\n=====================================================")
        // --- END LIFECYCLE LOGS ---

        // To perform a second metadata enrichment 30s after the song begins, in case the initial one failed.
        mediaItem?.mediaId?.let {
            scheduleMetadataRefresh(it)
        }

        if (consecutivePlaybackErr > 0) {
            consecutivePlaybackErr--
        }
        if (consecutiveUrlErrors > 0) {
            consecutiveUrlErrors = 0
        }


        val isNearEnd = player.mediaItemCount > 0 && (player.mediaItemCount - player.currentMediaItemIndex <= 5)
        val shouldAutoLoad = dataStore.get(AutoLoadMoreKey, true) && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        if (isNearEnd && shouldAutoLoad) {
            currentFullSongList?.let { fullList ->
                scope.launch {
                    if (player.mediaItemCount == 0) return@launch
                    val lastPlayedId = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
                    val nextSongs = withContext(Dispatchers.IO) {
                        val lastPlayedIndexInFullList = fullList.indexOfFirst { it.id == lastPlayedId }
                        if (lastPlayedIndexInFullList != -1 && lastPlayedIndexInFullList < fullList.size - 1) {
                            val nextStartIndex = lastPlayedIndexInFullList + 1
                            val nextEndIndex = (nextStartIndex + 10).coerceAtMost(fullList.size)
                            fullList.subList(nextStartIndex, nextEndIndex).map { it.toMediaMetadata() }
                        } else {
                            emptyList()
                        }
                    }
                    if (nextSongs.isNotEmpty()) {
                        queueBoard.enqueueEnd(nextSongs)
                    }
                }
            }
            when (val queue = currentContinuableQueue) {
                is GlobalRadioQueue -> {
                    scope.launch(Dispatchers.IO) {
                        val newSongs = queue.nextPage()
                        if (newSongs.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                queueBoard.enqueueEnd(newSongs)
                            }
                        }
                    }
                }
            }
        }
        val currentQueue = queueBoard.getCurrentQueue()

        Log.i(TAG, "onMediaItemTransition:  currentQueue?.playlistId = ${currentQueue?.playlistId}, currentContinuableQueue = $currentContinuableQueue")
        if (currentQueue?.playlistId == "GLOBAL_RADIO" && currentContinuableQueue == null) {
            Log.i(TAG, "onMediaItemTransition, we are in GLOBAL_RADIO and currentContinuableQueue is not null")
            currentContinuableQueue = GlobalRadioQueue(
                context = this@MusicService,
                db = database,
                localAndLikedSongLimit = dataStore.get(GlobalRadioLocalAndLikedSongsCountKey, 6),
                albumAndArtistLimit = dataStore.get(GlobalRadioArtistsCountKey, 3),
                songsPerArtistLimit = dataStore.get(GlobalRadioSongsCountKey, 3)
            )
            globalRadioHistory = GlobalRadioHistory()
            (currentContinuableQueue as GlobalRadioQueue).setHistory(globalRadioHistory!!)
        }


        queueBoard.setCurrQueuePosIndex(player.currentMediaItemIndex)
        if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
            player.shuffleModeEnabled && player.repeatMode == Player.REPEAT_MODE_ALL
        ) {
            scope.launch(SilentHandler) {
                delay(200)
                queueBoard.shuffleCurrent(player.mediaItemCount > 2)
                queueBoard.setCurrQueue()
            }
        }

        Log.i(TAG, "onMediaItemTransition: before Trigger extra info display for Global Radio tracks")
        // Trigger extra info display for Global Radio tracks
        mediaItem?.metadata?.let { metadata ->
            if (metadata.parentArtist != null) {
                Log.i(TAG, "onMediaItemTransition: before updateCurrentMediaMetadata")
                updateCurrentMediaMetadata(metadata, forceShowOrigin = true)
            }
            // Getting the Comments tag from local file, using tagLib, as MediaStore doesn't fetch it:
            Log.i(TAG, "onMediaItemTransition: before extractLocalTagsOnTheFly")
            extractLocalTagsOnTheFly(metadata)
            // Send the extra info to the player at the scheduled time:
            Log.i(TAG, "onMediaItemTransition: before startTimedTagDisplay")
            startTimedTagDisplay(metadata)
        }
        Log.i(TAG, "onMediaItemTransition: before updateNotification")
        updateNotification()

        // Trigger background pre-caching for upcoming tracks
        runProactivePreCache()

        // --- RADIO DJ ANNOUNCEMENT ---
        val isAuto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        val radioDJEnabled = dataStore.get(RadioDJEnabledKey, false)
        val isGlobalRadio = currentContinuableQueue is GlobalRadioQueue

        if (isAuto && radioDJEnabled && isGlobalRadio && mediaItem != null) {
            // Mute and stop immediately on the main thread to ensure no blips
            muteForDJ.value = true
            player.playWhenReady = false
            player.seekTo(0)

            offloadScope.launch {
                var metadata = mediaItem.metadata

                // If year or album is missing, try a FAST background fetch (max 1.5s)
                if (metadata != null && (metadata.year == null || metadata.album?.title?.startsWith("§") == true)) {
                    Log.i(TAG, "RADIO DJ: Metadata incomplete for '${metadata.title}'. Attempting fast enrichment...")
                    try {
                        withTimeoutOrNull(1500) {
                            val enrichment = MetadataEnricher.findEnrichment(metadata!!.title, metadata!!.artists.first().name)
                            if (enrichment != null) {
                                metadata = metadata!!.copy(
                                    year = enrichment.year,
                                    album = enrichment.albumTitle?.let { com.dd3boh.outertune.models.MediaMetadata.Album(id = enrichment.albumId ?: "", title = it) }
                                )
                                Log.i(TAG, "RADIO DJ: Fast enrichment success! Found year: ${metadata?.year}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "RADIO DJ: Enrichment error", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    metadata?.let { finalMetadata ->
                        radioDJ?.speakAnnouncement(
                            metadata = finalMetadata,
                            style = dataStore.get(RadioDJStyleKey, "natural"),
                            language = dataStore.get(RadioDJLanguageKey, SYSTEM_DEFAULT),
                            onFinished = {
                                muteForDJ.value = false
                                player.play()
                            }
                        )
                    } ?: run {
                        // Fallback: If metadata is null, just resume
                        muteForDJ.value = false
                        player.play()
                    }
                }
            }
        }
    }

    //    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
//        if (playbackState == STATE_IDLE) {
//            queuePlaylistId = null
//        }
//    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)

        // 1. The original logic from the file, to reset state when idle.

        // --- DEFINITIVE FIX for Race Condition ---
        // A session is only truly over if the player is idle AND has no more media to play.
        // This prevents the context from being wiped during a temporary error state.
//        if (playbackState == Player.STATE_IDLE && player.mediaItemCount == 0) {
//            queuePlaylistId = null
//            Log.d(TAG, "onPlaybackStateChanged............. setting queuePlaylistId to null because the player is idle and there is no error")
//        } else {
//            Log.d(TAG, "onPlaybackStateChanged.............. queuePlaylistId = $queuePlaylistId : player is not idle or there is an error, player state: ${Player.STATE_IDLE}, error: ${player.playerError}")
//        }
        // --- DEFINITIVE FIX for Context Wiping ---
        // A session is only truly over if the player is idle AND the user has cleared the queue.
        // Wiping on mediaItemCount == 0 is dangerous because it happens during setMediaItems transitions.
        if (playbackState == Player.STATE_IDLE && qbInit.value && queueBoard.getCurrentQueue() == null) {
            queuePlaylistId = null
            Log.i(TAG, "onPlaybackStateChanged: Session context cleared because queue is empty.")
        }else {
             Log.i(TAG, "onPlaybackStateChanged.............. queuePlaylistId = $queuePlaylistId : player is not idle or there is an error, player state: ${Player.STATE_IDLE}, error: ${player.playerError}")
         }

        // 2. The new logic to handle continuous Global Radio playback.
        if (playbackState == Player.STATE_ENDED) {
            Log.i(TAG, "LIFECYCLE: Song reached the end naturally. \n=====================================================")
            val currentQueue = queueBoard.getCurrentQueue()
            // Check if the queue that just ended was our Global Radio.

            Log.i(TAG, "onPlaybackStateChanged:  currentQueue?.playlistId = ${currentQueue?.playlistId}")
            if (currentQueue?.playlistId == "GLOBAL_RADIO") {
                Log.i(TAG, "onPlaybackStateChanged Playback ended. Triggering Global Radio auto load.")

                // Use the continuable queue object to fetch the next page of songs.
                currentContinuableQueue?.let { continuableQueue ->
                    scope.launch(SilentHandler) {
                        val newSongs = continuableQueue.nextPage()
                        // Add the new songs to the end of the current queue.
                        queueBoard.enqueueEnd(newSongs)
                        Log.i(TAG, "onPlaybackStateChanged Global Radio: Enqueued ${newSongs.size} new songs.")

                        // After adding the songs, the player is still in STATE_ENDED.
                        // We must tell it to prepare and play to continue.
                        player.prepare()
                        player.play()
                    }
                } ?: Log.e(TAG, "onPlaybackStateChanged Cannot load more Global Radio songs: currentContinuableQueue is null. Service state may have been lost.")
            } else {
                Log.i(TAG, "onPlaybackStateChanged Current queue is NOT Global Radio.")
            }
        }
        // --- END MERGED LOGIC ---
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED
            )
        ) {
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                openAudioEffectSession()
            } else {
                closeAudioEffectSession()
                if (!player.playWhenReady) {
                    waitingForNetworkConnection.value = false
                }
            }
        }
        if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
        }
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        offloadScope.launch {
            val mediaItem =
                eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
            var minPlaybackDur = (dataStore.get(minPlaybackDurKey, 30).toFloat() / 100)
            // ensure within bounds
            if (minPlaybackDur >= 1f) {
                minPlaybackDur = 0.99f // Ehhh 99 is good enough to avoid any rounding errors
            } else if (minPlaybackDur < 0.01f) {
                minPlaybackDur = 0.01f // Still want "spam skipping" to not count as plays
            }

//            val playRatio =
//                playbackStats.totalPlayTimeMs.toFloat() / ((mediaItem.metadata?.duration?.times(1000))
//                    ?: -1)
//            Log.d(TAG, "Playback ratio: $playRatio Min threshold: $minPlaybackDur")
//            if (playRatio >= minPlaybackDur && !dataStore.get(PauseListenHistoryKey, false)) {
 //
            // TEMPORARY TEST CHANGE: Use 3 seconds instead of percentage
            val isPlayedLongEnough = playbackStats.totalPlayTimeMs >= 3000

            Log.i(TAG, "LIFECYCLE: Playback time: ${playbackStats.totalPlayTimeMs}ms. Recording to history: $isPlayedLongEnough")

            if (isPlayedLongEnough && !dataStore.get(PauseListenHistoryKey, false)) {
                database.query {
                    incrementPlayCount(mediaItem.mediaId)
                    try {
                        insert(
                            Event(
                                songId = mediaItem.mediaId,
                                timestamp = LocalDateTime.now(),
                                playTime = playbackStats.totalPlayTimeMs
                            )
                        )
                    } catch (_: SQLException) {
                    }
                }

                // TODO: support playlist id
                val ytHist = mediaItem.metadata?.isLocal != true && !dataStore.get(
                    PauseRemoteListenHistoryKey,
                    false
                )
                Log.d(TAG, "Trying to register remote history: $ytHist")
                if (ytHist) {
                    val playbackUrl =
                        YTPlayerUtils.playerResponseForMetadata(mediaItem.mediaId, null)
                            .getOrNull()?.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    Log.d(TAG, "Got playback url: $playbackUrl")
                    playbackUrl?.let {
                        YouTube.registerPlayback(null, playbackUrl)
                            .onFailure {
                                reportException(it)
                            }
                    }
                }
            }
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        offloadScope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        val q = queueBoard.getCurrentQueue()
        player.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(player.mediaItemCount))
        if (q == null || q.shuffled == shuffleModeEnabled) return
        triggerShuffle()
    }


    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        // FG keep alive
        if (player.isPlaying || !dataStore.get(KeepAliveKey, false)) {
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Terminating MusicService.")
        radioDJ?.shutdown()
        deInitQueue()

        mediaSession.player.stop()
        mediaSession.release()
        mediaSession.player.release()
        if (instance == this) instance = null
        super.onDestroy()
        Log.i(TAG, "Terminated MusicService.")
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved called")
        if (dataStore.get(StopMusicOnTaskClearKey, true) && !dataStore.get(KeepAliveKey, false)) {
            Log.i(TAG, "onTaskRemoved kill")
            pauseAllPlayersAndStopSelf()
        } else {
            Log.i(TAG, "onTaskRemoved def")
            super.onTaskRemoved(rootIntent)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    /**
     * Executes a secondary metadata fetch 30 seconds into a song to ensure
     * that information like the year and high-res artwork are correctly retrieved
     * and displayed on all screens (Phone and Android Auto).
     */
    private fun scheduleMetadataRefresh(mediaId: String) {
        backgroundEnrichmentJob?.cancel()
        backgroundEnrichmentJob = scope.launch(Dispatchers.IO) {
            delay(30000) // 30 second delay
            Log.i(TAG, "ENRICH METADATA : calling findEnrichment after 30s delay for ${mediaId}")
            // Verify the song is still the one playing and player is active

            if (!isNetworkConnected.value) return@launch

            // Fetch song details from DB
            val songWithArtists = database.song(mediaId).firstOrNull() ?: return@launch
            val isSynthetic = songWithArtists.song.albumId?.startsWith("local_tag_album_") == true
            val needsEnrichment = songWithArtists.song.year == null || isSynthetic
            if (songWithArtists.song.isLocal && !needsEnrichment) return@launch


            // Check if we still need enrichment (maybe PlayerVM already did it)
            Log.i(TAG, "ENRICH METADATA : songWithArtists.song.year = ${songWithArtists.song.year}")
            if (songWithArtists.song.year != null && songWithArtists.song.thumbnailUrl?.startsWith("http") == true){
                Log.i(TAG, "ENRICH METADATA : no need to refresh metadata")
                return@launch
            }

            val shouldExecute = withContext(Dispatchers.Main) {
                player.currentMediaItem?.mediaId == mediaId &&
                        player.playerError == null &&
                        (player.isPlaying || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            }
            if (!shouldExecute) return@launch


            val result = MetadataEnricher.findEnrichment(songWithArtists.song.title, songWithArtists.artists.firstOrNull()?.name ?: "")

            if (result?.albumPage != null && isActive) {
                // Save to DB (album and song with the discovered year)
                database.query {
                    insert(result.albumPage.album.copy(year = result.year))
                    update(songWithArtists.song.copy(
                        year = songWithArtists.song.year ?: result.year,
                        albumName = if (isSynthetic) result.albumTitle else songWithArtists.song.albumName,
                        trackNumber = songWithArtists.song.trackNumber ?: result.trackNumber
                    ))
                }

                // Refresh displays
                database.song(mediaId).firstOrNull()?.let { updatedSong ->
                    withContext(Dispatchers.Main) {
                        Log.i(TAG, "ENRICH METADATA : Background enrichment success for artist ${updatedSong.artists.firstOrNull()?.name} '${updatedSong.song.title}' [${updatedSong.song.year}]. Refreshing session.")
                        updateCurrentMediaMetadata(updatedSong.toMediaMetadata())
                    }
                }
            }
        }
    }




    /**
     * Proactively downloads the current and next few songs in the queue into the PlayerCache.
     * This ensures playback continues during internet dead zones.
     */
    private fun runProactivePreCache() {
        if (waitingForNetworkConnection.value) return

        val currentIndex = try { player.currentMediaItemIndex } catch (e: Exception) { -1 }
        val currentQueue = queueBoard.getCurrentQueue()

        if (currentIndex == -1 || currentQueue == null) return

        val upcomingItems = try {
            currentQueue.getCurrentQueueShuffled()
                .drop(currentIndex)
                .filter { !it.isLocal && it.id.isNotBlank() }
                .take(5) // 5 songs to cache
        } catch (e: Exception) { emptyList() }

        if (upcomingItems.isEmpty()) return

        preCacheJob?.cancel()
        preCacheJob = scope.launch(Dispatchers.IO) {
            preCacheLock.withLock {
                if (!isInternetActuallyAvailable()) {
                    Log.i(TAG, "PRE-CACHE: Internet too weak or offline. Standing down.")
                    return@withLock
                }

                Log.i(TAG, "PRE-CACHE: Starting background task for ${upcomingItems.size} items.")

                for (item in upcomingItems) {
                    if (!isActive || !isNetworkConnected.value) {
                        Log.i(TAG, "PRE-CACHE: Aborting current batch (Interrupt detected).")
                        break
                    }

                    try {
                        val metadata = playerCache.getContentMetadata(item.id)
                        val length = metadata.get(androidx.media3.datasource.cache.ContentMetadata.KEY_CONTENT_LENGTH, C.LENGTH_UNSET.toLong())

                        val isFullyCached = length != C.LENGTH_UNSET.toLong() && playerCache.isCached(item.id, 0L, length)

                        if (isFullyCached) {
                            Log.i(TAG, "PRE-CACHE: Skipping '${item.title}' (Already fully cached).")
                            continue
                        }

                        Log.i(TAG, "PRE-CACHE: Resolving/Resuming '${item.title}'.........................")

                        val audioQuality = dataStore[AudioQualityKey]?.let { AudioQuality.valueOf(it) }
                            ?: AudioQuality.AUTO

                        val playbackData = YTPlayerUtils.playerResponseForPlayback(
                            videoId = item.id,
                            audioQuality = audioQuality,
                            connectivityManager = connectivityManager
                        ).getOrNull() ?: continue

                        val streamUrl = playbackData.streamUrl
                        if (streamUrl.isBlank()) continue

                        val headers = YTPlayerUtils.getPlaybackHeaders(playbackData.userAgent, playbackData.visitorData)
                        val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                            .setUri(streamUrl.toUri())
                            .setKey(item.id)
                            .setHttpRequestHeaders(headers)
                            .setFlags(androidx.media3.datasource.DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                            .build()

                        val upstreamFactory = DefaultDataSource.Factory(
                            this@MusicService,
                            OkHttpDataSource.Factory(YTPlayerUtils.httpClient)
                        )

                        val cacheWriter = androidx.media3.datasource.cache.CacheWriter(
                            CacheDataSource(playerCache, upstreamFactory.createDataSource()),
                            dataSpec, null, null
                        )

                        Log.i(TAG, "PRE-CACHE: Downloading '${item.title}'...........................................................")
                        withTimeoutOrNull(60000) { cacheWriter.cache() }
                        Log.i(TAG, "PRE-CACHE: Success for '${item.title}'===========================================================")

                        delay(1000)

                    } catch (e: Exception) {
                        Log.e(TAG, "PRE-CACHE: Task failed for '${item.title}': ${e.message}")
                        // If network error, wait 5s then CONTINUE to next track
                        if (e is java.net.SocketException || e is java.io.IOException) {
                            delay(5000)
                        }
                    }
                }
            }
        }
    }


    companion object {

        @Volatile
        var instance: MusicService? = null

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val SEARCH = "search"

        const val CHANNEL_ID = "music_channel_01"
        const val CHANNEL_NAME = "fgs_workaround"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L

        const val COMMAND_GET_BINDER = "GET_BINDER"

        const val GLOBAL_RADIO = "global_radio"
        const val YOUTUBE_RESET = "youtube_reset"
        const val LAST_ACTIVE_TIME_KEY = "last_active_time"
        const val IDLE_RESET_THRESHOLD_MS = 4 * 60 * 60 * 1000L // 4 hours
    }
}
