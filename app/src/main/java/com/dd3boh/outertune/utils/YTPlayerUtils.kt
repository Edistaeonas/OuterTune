/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.validateStatus
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.ORIGIN_YOUTUBE_MUSIC
import com.zionhuang.innertube.models.YouTubeClient.Companion.REFERER_YOUTUBE_MUSIC
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    // Prefer IPv4, but allow fallback to IPv6 if lookup is slow/empty
                    val addresses = Dns.SYSTEM.lookup(hostname)
                    val ipv4 = addresses.filter { it is Inet4Address }
                    ipv4.ifEmpty { addresses }
                } catch (e: Exception) {
                    throw UnknownHostException(e.message)
                }
            }
        })
        .connectionPool(okhttp3.ConnectionPool(15, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private const val POTOKEN_CACHE_TTL_MS = 60_000L
    private const val WEB_DECIPHER_FAILURE_COOLDOWN_MS = 120_000L

    private data class CachedPoToken(
        val result: PoTokenResult,
        val createdAtMs: Long,
    )

    private val poTokenCache = ConcurrentHashMap<String, CachedPoToken>()
    private val inFlightPoToken = ConcurrentHashMap<String, CompletableDeferred<PoTokenResult?>>()

    private val resolveMutexByVideo = ConcurrentHashMap<String, Mutex>()
    private val recentWebDecipherFailures = ConcurrentHashMap<String, Long>()

    /**
     * How long a video keeps skipping WEB_REMIX after one of its streams was rejected. Long enough
     * to get past the rejection, short enough that a transient CDN failure does not pin the video to
     * a lower-quality client for the rest of the session.
     */
    private const val WEB_REMIX_FAILURE_TTL_MS = 5 * 60 * 1000L

    /** videoId -> when its WEB_REMIX stream was last rejected mid-playback. */
    private val webRemixFailures = ConcurrentHashMap<String, Long>()

    /**
     * Records that [videoId]'s WEB_REMIX stream url was refused while playing.
     */
    fun markWebRemixFailed(videoId: String) {
        webRemixFailures[videoId] = System.currentTimeMillis()
    }

    private fun hasRecentWebRemixFailure(videoId: String): Boolean {
        val failedAt = webRemixFailures[videoId] ?: return false
        if ((System.currentTimeMillis() - failedAt) !in 0 until WEB_REMIX_FAILURE_TTL_MS) {
            webRemixFailures.remove(videoId, failedAt)
            return false
        }
        return true
    }

    /**
     * Client used for metadata and the initial stream response.
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    /**
     * Clients tried for the stream, in order.
     */
//    private val STREAM_CLIENTS: Array<YouTubeClient> = arrayOf(
//        ANDROID,
//        IOS,
//        TVHTML5,
//        ANDROID_VR,
//        WEB_REMIX,
//    )

    /**
     * Clients tried for the stream, in order. Separate from [MAIN_CLIENT], which only has to be good
     * for metadata: the VR builds hand out the longest-lived urls for music, so they go first even
     * though the metadata keeps coming from the signed-in client. When this list reaches
     * [MAIN_CLIENT] its already-fetched response is reused instead of asking again.
     */
    private val STREAM_CLIENTS: Array<YouTubeClient> = arrayOf(
        // First on purpose: its urls play a track through, while the others get cut off partway.
        VISIONOS,
        ANDROID_VR_1_65_10,
        ANDROID_VR_1_43_32,
        WEB_REMIX,
        TVHTML5,
        IOS,
        // ANDROID stays out: its player request answers 400. Measured 2026-08-19.
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String = "unknown",
        val streamHeaders: Map<String, String> = emptyMap(),
    )

    /** Identifies a media request as coming from the client the stream url was issued for. */
    private fun YouTubeClient.streamHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        when (clientName) {
            "WEB_REMIX" -> {
                put("Referer", REFERER_YOUTUBE_MUSIC)
                put("Origin", ORIGIN_YOUTUBE_MUSIC)
            }
            "WEB_CREATOR" -> {
                put("Referer", "https://studio.youtube.com/")
                put("Origin", "https://studio.youtube.com")
            }
            else -> {
                put("Referer", "https://www.youtube.com/")
                put("Origin", "https://www.youtube.com")
            }
        }
    }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runSingleFlight(videoId) {
        runCatching {

        if (YouTube.visitorData == null) {
            Log.w(TAG, "SESSION_LOG: No session found. Attempting fast handshake...")
            YouTube.visitorData().onSuccess {
                YouTube.visitorData = it
                Log.i(TAG, "SESSION_LOG: Fast handshake success: $it")
            }
            if (YouTube.visitorData == null) {
                runCatching { YouTube.home() }
            }
        } else {
            Log.i(TAG, "SESSION_LOG: Using existing session: ${YouTube.visitorData}")
        }

        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val sessionId = YouTube.visitorData

        val (webPlayerPot, webStreamingPot) = getWebClientPoTokenOrNull(videoId, sessionId)?.let {
            Pair(it.playerRequestPoToken, it.streamingDataPoToken)
        } ?: Pair(null, null)

        val mainPlayerResponse =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, webPlayerPot)
                .getOrThrow()

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamClient: String? = null
        var streamHeaders: Map<String, String> = emptyMap()

        var streamPlayerResponse: PlayerResponse? = null
        var hadPlayableStatus = false
        for ((clientIndex, client) in STREAM_CLIENTS.withIndex()) {
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamClient = null

            Log.d(TAG, "Trying stream client ${clientIndex + 1}/${STREAM_CLIENTS.size}: ${client.clientName}")

            if (client.loginRequired && YouTube.cookie == null) continue
            if (client.clientName == "WEB_REMIX" && hasRecentWebRemixFailure(videoId)) continue
            if (client.clientName == "WEB_REMIX" && hasRecentWebDecipherFailure(videoId)) {
                Log.d(TAG, "[$videoId] [WEB_REMIX] skip: decipher cooldown active")
                continue
            }

            streamPlayerResponse = if (client == MAIN_CLIENT) {
                mainPlayerResponse
            } else {
                YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot).getOrNull()
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                hadPlayableStatus = true
                format = findFormat(streamPlayerResponse, audioQuality, connectivityManager)
                streamUrl = format?.let { findUrlOrNull(it, videoId, client) }
                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds

                if (format == null) {
                    Log.d(TAG, "[$videoId] [${client.clientName}] skip: no audio format")
                    continue
                }
                if (streamUrl == null) {
                    Log.d(TAG, "[$videoId] [${client.clientName}] skip: no stream url")
                    continue
                }
                if (streamExpiresInSeconds == null) {
                    Log.d(TAG, "[$videoId] [${client.clientName}] skip: no expiresInSeconds")
                    continue
                }

                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot"
                }

                streamClient = client.clientName
                streamHeaders = client.streamHeaders()

                if (clientIndex == STREAM_CLIENTS.lastIndex || validateStatus(streamUrl, streamHeaders)) {
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream")
                    break
                } else {
                    Log.d(TAG, "[$videoId] [${client.clientName}] skip: stream validation failed")
                }
            } else {
                val status = streamPlayerResponse?.playabilityStatus?.status
                Log.d(TAG, "[$videoId] [${client.clientName}] skip: playability=$status")
            }
        }

        if (!hadPlayableStatus) {
            val reason = streamPlayerResponse?.playabilityStatus?.reason ?: "no client returned playability=OK"
            throw PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }
        if (streamExpiresInSeconds == null || format == null || streamUrl == null) {
            throw Exception("No playable stream URL from available clients")
        }

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            streamClient ?: MAIN_CLIENT.clientName,
            streamHeaders,
        )
    }
    }

    suspend fun playerResponseForMetadata(videoId: String, playlistId: String? = null): Result<PlayerResponse> {
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val webPlayerPot = getWebClientPoTokenOrNull(videoId, YouTube.visitorData)?.playerRequestPoToken
        return YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp, webPlayerPot)
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? =
        playerResponse.streamingData?.adaptiveFormats?.filter { it.isAudio }?.maxByOrNull {
            it.bitrate * when (audioQuality) {
                AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                AudioQuality.HIGH -> 1
                AudioQuality.LOW -> -1
            } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
        }

    private fun validateStatus(url: String, headers: Map<String, String> = emptyMap()): Boolean {
        return try {
            val requestBuilder = okhttp3.Request.Builder()
                .header("Range", "bytes=0-0")
                .url(url)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "stream validation failed: HTTP ${response.code}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? =
        NewPipeUtils.getSignatureTimestamp(videoId).onFailure { reportException(it) }.getOrNull()

    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient,
    ): String? {
        // Non-web clients: prefer direct URL or minimally reconstructed cipher URL;
        // avoid WEB JS deobfuscation fallback for these clients.
        if (client.clientName != "WEB_REMIX") {
            format.url?.let {
                Log.d(TAG, "[$videoId] [${client.clientName}] URL path=direct_url")
                return it
            }

            format.signatureCipher?.let { cipher ->
                val params = parseQueryString(cipher)
                val baseUrl = params["url"]
                val sp = params["sp"] ?: "signature"
                val sig = params["sig"]
                val s = params["s"]

                if (baseUrl != null && sig != null) {
                    val sep = if (baseUrl.contains('?')) '&' else '?'
                    val resolved = "$baseUrl$sep$sp=$sig"
                    Log.d(TAG, "[$videoId] [${client.clientName}] URL path=cipher_url_sig")
                    return resolved
                }

                if (baseUrl != null && s != null) {
                    Log.d(TAG, "[$videoId] [${client.clientName}] URL path=cipher_requires_js_skip")
                    return null
                }

                if (baseUrl != null) {
                    Log.d(TAG, "[$videoId] [${client.clientName}] URL path=cipher_url_only")
                    return baseUrl
                }
            }

            Log.d(TAG, "[$videoId] [${client.clientName}] URL path=none_non_web")
            return null
        }

        val npResult = NewPipeUtils.getStreamUrl(format, videoId)
        npResult.getOrNull()?.let {
            Log.d(TAG, "[$videoId] [${client.clientName}] URL path=web_js_ok")
            return it
        }

        npResult.exceptionOrNull()?.let {
            Log.d(TAG, "[$videoId] [${client.clientName}] URL path=web_js_fail")
            recentWebDecipherFailures[videoId] = System.currentTimeMillis()
            reportException(it)
        }
        return null
    }

    private suspend fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?): PoTokenResult? {
        if (sessionId == null) return null
        val cacheKey = "$videoId|$sessionId"
        val now = System.currentTimeMillis()

        poTokenCache[cacheKey]?.let { cached ->
            if (now - cached.createdAtMs in 0 until POTOKEN_CACHE_TTL_MS) {
                return cached.result
            } else {
                poTokenCache.remove(cacheKey, cached)
            }
        }

        inFlightPoToken[cacheKey]?.let { inFlight ->
            return inFlight.await()
        }

        val deferred = CompletableDeferred<PoTokenResult?>()
        val previous = inFlightPoToken.putIfAbsent(cacheKey, deferred)
        if (previous != null) return previous.await()

        return try {
            val result = withContext(Dispatchers.IO) {
                try {
                    withTimeout(5000) {
                        poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[$videoId] PoToken timed out", e)
                    null
                }
            }
            if (result != null) {
                poTokenCache[cacheKey] = CachedPoToken(result, System.currentTimeMillis())
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlightPoToken.remove(cacheKey, deferred)
        }
    }

    private suspend fun <T> runSingleFlight(videoId: String, block: suspend () -> Result<T>): Result<T> {
        val mutex = resolveMutexByVideo.getOrPut(videoId) { Mutex() }
        return mutex.withLock {
            block()
        }
    }

    private fun hasRecentWebDecipherFailure(videoId: String): Boolean {
        val failedAt = recentWebDecipherFailures[videoId] ?: return false
        val age = System.currentTimeMillis() - failedAt
        return if (age in 0 until WEB_DECIPHER_FAILURE_COOLDOWN_MS) {
            true
        } else {
            recentWebDecipherFailures.remove(videoId, failedAt)
            false
        }
    }

    fun nukeNetworkPool() {
        Log.w(TAG, "Nuking OkHttp connection pool to clear stale sockets.")
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.cancelAll()
    }

    suspend fun isInternetActuallyAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://connectivitycheck.gstatic.com/generate_204")
                .header("Connection", "close")
                .build()

            val client = httpClient.newBuilder()
                .connectTimeout(4500, TimeUnit.MILLISECONDS)
                .readTimeout(4500, TimeUnit.MILLISECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                response.code == 204
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getPlaybackHeaders(userAgent: String, visitorData: String? = null): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to userAgent)
        val vData = visitorData ?: YouTube.visitorData
        vData?.let { headers["X-Goog-Visitor-Id"] = it }
        return headers
    }
}
