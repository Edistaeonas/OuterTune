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
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Dns
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.UnknownHostException
import okhttp3.Request
import java.util.concurrent.TimeUnit

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

//    private val httpClient = OkHttpClient.Builder()
//        .proxy(YouTube.proxy)
//        .build()


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
                    // Corrected: explicitly use the java.net class
                    throw UnknownHostException(e.message)
                }
            }
        })
        .connectionPool(okhttp3.ConnectionPool(15, 5, java.util.concurrent.TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS) // Reduced from 30s to speed up the skip to the next song in case of network unavailability
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()


    private val poTokenGenerator = PoTokenGenerator()

    private val QUICK_FETCH_CLIENT: YouTubeClient = WEB_REMIX // for metadata
    private val MAIN_CLIENT: YouTubeClient get() = ANDROID_VR_NO_AUTH // for music

    /**
     * Fallback clients for stream extraction.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> get() = arrayOf(
        ANDROID,
        IOS
    )

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val userAgent: String,
        val visitorData: String?,
    )

    // -----------------------------------------------------------------------------------------



    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {

        // 1. Mandatory Session Handshake (Only if missing)
        if (YouTube.visitorData == null) {
            Log.w(TAG, "SESSION_LOG: No session found. Attempting fast handshake...")
            // Try the lighter/faster visitor data endpoint first
            YouTube.visitorData().onSuccess {
                YouTube.visitorData = it
                Log.i(TAG, "SESSION_LOG: Fast handshake success: $it")
            }

            if (YouTube.visitorData == null) {
                // Fallback to home() only if fast method fails
                repeat(2) { attempt ->
                    runCatching { YouTube.home() }
                    if (YouTube.visitorData != null) return@repeat
                    delay(1000L * (attempt + 1))
                }
            }
        } else {
            Log.i(TAG, "SESSION_LOG: Using existing session: ${YouTube.visitorData}")
        }

        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val webPlayerPot = getWebClientPoTokenOrNull(videoId, YouTube.visitorData ?: YouTube.dataSyncId)?.playerRequestPoToken

        // 2. Metadata Phase: Quick fetch (Short timeout so it never blocks the music)
        val mainResponse = withTimeoutOrNull(3000) {
            YouTube.player(videoId, playlistId, QUICK_FETCH_CLIENT, signatureTimestamp, webPlayerPot).getOrNull()
        }

        // 3. Stream Search Phase: Try MAIN_CLIENT, then fall back to others if blocked
        val clientsToTry = listOf(MAIN_CLIENT) + STREAM_FALLBACK_CLIENTS
        var response: PlayerResponse? = null
        var lastErrorReason: String? = null
        var activeWebPlayerPot = webPlayerPot // Start with the initial token
        var winningUserAgent = MAIN_CLIENT.userAgent

        for (client in clientsToTry) {
            Log.i(TAG, "Attempting extraction with client: ${client.clientName}")

            // If the previous client was flagged, we ensure we get a NEW session AND a NEW PO Token
//2026.03.11
//  // FIX: Remove session reset from the loop.
//    // Only perform a handshake at the start of the function (which you already do).
//            if (YouTube.visitorData == null) {
//                Log.i(TAG, "YouTube.visitorData is null so we call YouTube.home() ")
//                Log.i(TAG, "Session is empty, performing single handshake for ${client.clientName}")
//                runCatching { YouTube.home() }
//                Log.i(TAG, "Session wiped, performing PO Token generation...")
//                // Re-generate PO Token for the new visitorData
//                activeWebPlayerPot = getWebClientPoTokenOrNull(videoId, YouTube.visitorData ?: YouTube.dataSyncId)?.playerRequestPoToken
//                delay(1500)
//            }

            Log.i(TAG, "Attempting extraction with client: ${client.clientName}")
            val result = YouTube.player(
                videoId = videoId,
                playlistId = null,
                client = client,
                signatureTimestamp = signatureTimestamp,
                webPlayerPot = activeWebPlayerPot // important to be not null and synchronised, for Android VR bot check.
            ).getOrNull()

//            if (result?.playabilityStatus?.status == "OK") {
//                response = result
//                winningUserAgent = client.userAgent
//                Log.i(TAG, "Successfully fetched stream using client: ${client.clientName} userAgent: $winningUserAgent")
//                break
//            } else {
//                lastErrorReason = result?.playabilityStatus?.reason
//                Log.w(TAG, "Client ${client.clientName} failed: $lastErrorReason")
//
//
//                // If flagged as bot, just try the next client identity in the list.
//                // Switching from VR to Mobile headers is often enough.
//                if (lastErrorReason?.contains("bot", ignoreCase = true) == true) {
//                    YouTube.visitorData = null
//                    delay(1000)
//                    continue
//                }
//            }
            // CASE 1: YouTube returned "OK"
            if (result?.playabilityStatus?.status == "OK") {
                val format = result.streamingData?.adaptiveFormats?.filter { it.isAudio }?.maxByOrNull { it.bitrate }
                val streamUrl = format?.let { findUrlOrNull(it, videoId) }

                if (streamUrl != null) {
                    response = result
                    winningUserAgent = client.userAgent
                    Log.i(TAG, "Successfully fetched stream using client: ${client.clientName}")
                    break
                } else {
                    Log.w(TAG, "Client ${client.clientName} returned OK but extraction failed (no URL). Resetting session and trying fallback...")

                    if (client.clientName == "ANDROID_VR") {
                        com.zionhuang.innertube.models.YouTubeClient.rotateVrVersion()
                    }

                    // CRITICAL: Wipe the flagged session so the next client gets a fresh one
                    YouTube.visitorData = null
                    YouTube.visitorData().onSuccess {
                        YouTube.visitorData = it
                        Log.i(TAG, "SESSION_LOG: Soft-block recovery - New visitorData obtained")
                    }
                    delay(800)
                    continue
                }
            }
            // CASE 2: YouTube returned an error (like "Bot")
            else {
                lastErrorReason = result?.playabilityStatus?.reason
                Log.w(TAG, "Client ${client.clientName} failed: $lastErrorReason")

                // THE KEY RESET: If flagged as bot, wipe the session
                // so the NEXT client in the loop gets a fresh start.
                if (lastErrorReason?.contains("bot", ignoreCase = true) == true) {
                    YouTube.visitorData = null
                    delay(1000)
                    continue
                }
            }
        }

        // Ensure we actually got a valid response after trying all clients
        val finalResponse = response ?: throw IOException("All extraction clients failed. Last reason: $lastErrorReason")

        val allFormats = (finalResponse.streamingData?.adaptiveFormats ?: emptyList()) +
                (finalResponse.streamingData?.formats ?: emptyList())

        val format = allFormats.filter { it.isAudio }.maxByOrNull { it.bitrate }
            ?: throw IOException("No audio format found")

        val streamUrl = findUrlOrNull(format, videoId) ?: throw IOException("Extraction failed")

        PlaybackData(
            audioConfig = mainResponse?.playerConfig?.audioConfig ?: finalResponse.playerConfig?.audioConfig,
            videoDetails = mainResponse?.videoDetails ?: finalResponse.videoDetails,
            playbackTracking = mainResponse?.playbackTracking ?: finalResponse.playbackTracking,
            format = format,
            streamUrl = streamUrl,
            streamExpiresInSeconds = finalResponse.streamingData?.expiresInSeconds ?: 0,
            userAgent = winningUserAgent,
            visitorData = YouTube.visitorData
        )
    }

// -----------------------------------------------------------------------------------------

    suspend fun playerResponseForMetadata(videoId: String, playlistId: String? = null): Result<PlayerResponse> =
        YouTube.player(videoId, playlistId, client = WEB_REMIX)

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

    private fun validateStatus(url: String): Boolean {
        return try {
            val request = okhttp3.Request.Builder().url(url).addHeader("Range", "bytes=0-1").build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    private fun getSignatureTimestampOrNull(videoId: String): Int? =
        NewPipeUtils.getSignatureTimestamp(videoId).onFailure { reportException(it) }.getOrNull()

    private fun findUrlOrNull(format: PlayerResponse.StreamingData.Format, videoId: String): String? {
        // If the URL is already provided and not scrambled, use it immediately
        if (format.url != null) return format.url
        // Otherwise use the extractor
        return NewPipeUtils.getStreamUrl(format, videoId).getOrNull()
    }

    private suspend fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?): PoTokenResult? {
        if (sessionId == null) return null
        return withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(5000) {
                    poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$videoId] PoToken timed out", e)
                null
            }
        }
    }

    /**
     * Forcefully clears the connection pool and dispatcher.
     * Essential for recovering from 0:00 hangs after network transitions.
     */
    fun nukeNetworkPool() {
        Log.w(TAG, "Nuking OkHttp connection pool to clear stale sockets.")
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.cancelAll()
    }

    /**
     * Performs a robust check to see if the network is strong enough for data transfer.
     * Uses the shared OkHttpClient to ensure proxy/DNS settings are respected.
     * Strict timeout helps identify weak (EDGE/GPRS) connections.
     */
    suspend fun isInternetActuallyAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://connectivitycheck.gstatic.com/generate_204")
                .header("Connection", "close")
                .build()

            // 4.5s timeout: strict enough to identify weak connections that would fail streaming
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

    /**
     * Returns the required HTTP headers for YouTube playback and caching.
     */
    fun getPlaybackHeaders(userAgent: String, visitorData: String? = null): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to userAgent)
        val vData = visitorData ?: YouTube.visitorData
        vData?.let { headers["X-Goog-Visitor-Id"] = it }
        return headers
    }
}
