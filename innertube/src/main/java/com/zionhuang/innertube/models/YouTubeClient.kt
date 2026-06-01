package com.zionhuang.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,      // NEW
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null, // NEW
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val useWebPoTokens: Boolean = false,
    val isEmbedded: Boolean = false,
    // val origin: String? = null,
    // val referer: String? = null,
) {
    fun toContext(
        locale: YouTubeLocale,
        visitorData: String?,
        dataSyncId: String?,
        // Add these optional parameters
        deviceOsVersion: String = "13",
        deviceSdkVersion: Int = 33,
        deviceModel: String = "Mobile"
    ) = Context(
        client = Context.Client(
            clientName = clientName,
            clientVersion = clientVersion,
            // If it's MWEB, pretend we are on iOS to match the User-Agent above
            osName = if (clientName == "MWEB") "iOS" else (osName ?: "Android"),
            osVersion = if (clientName == "MWEB") "12.4" else (osVersion ?: deviceOsVersion),
            androidSdkVersion = androidSdkVersion ?: deviceSdkVersion,
            model = deviceModel,
            gl = locale.gl,
            hl = locale.hl,
            visitorData = visitorData
        ),
        user = Context.User(
            onBehalfOfUser = if (loginSupported) dataSyncId else null
        ),
    )

    companion object {

        // Different version of the Android VR client, that we rotate when resetting the YouTube session, in case of network issues. (Youtube BotGuard).
        const val VR_CLIENT_LATEST_VERSION = "1.72.15"
        const val VR_CLIENT_FALLBACK_VERSION = "1.72.14"
        const val VR_CLIENT_ALT1_VERSION = "1.70.10"
        const val VR_CLIENT_ALT2_VERSION = "1.61.48"
        const val WEB_VER_KEY = "1.20260310.01.00"
        //const val ANDROID_VER_KEY = "21.05.34"
        //const val IOS_VER_KEY = "19.48.3"
        const val ANDROID_VER_KEY = "19.10.35"
        const val IOS_VER_KEY = "19.10.2"

        const val USER_AGENT_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        const val ORIGIN_YOUTUBE_MUSIC = "https://music.youtube.com"
        const val REFERER_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/"
        const val API_URL_YOUTUBE_MUSIC = "$ORIGIN_YOUTUBE_MUSIC/youtubei/v1/"

        // List of YouTube Clients :

        // --- DYNAMIC OVERRIDES ---
        // These can be changed by the user in Settings
        var currentVrVersion = VR_CLIENT_FALLBACK_VERSION
        var currentWebVersion = WEB_VER_KEY
        var currentAndroidVersion = ANDROID_VER_KEY
        var currentIosVersion = IOS_VER_KEY

        // 1. Metadata & Normal Web (High Richness)
        val WEB_REMIX get() = YouTubeClient(
            clientName = "WEB_REMIX",
            clientVersion = currentWebVersion,
            clientId = "67",
            userAgent = USER_AGENT_WEB,
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
        )
        // 2. Stable Android and IOS (The fallback for streams)
        val ANDROID get() = YouTubeClient(
            clientName = "ANDROID",
            clientVersion = currentAndroidVersion,
            clientId = "3",
            userAgent = "com.google.android.youtube/$currentAndroidVersion (Linux; U; Android 11) gzip",
            loginSupported = true,
            //useSignatureTimestamp = false
            useSignatureTimestamp = true // Changed from false on 21.05.2026
        )

        val IOS get() = YouTubeClient(
            clientName = "IOS",
            clientVersion = currentIosVersion,
            clientId = "5",
            userAgent = "com.google.ios.youtube/$currentIosVersion (iPhone16,2; U; CPU iOS 18_1 like Mac OS X;)",
            osVersion = "18.1",
            loginSupported = true,
            //useSignatureTimestamp = false
            useSignatureTimestamp = true // Changed from false on 21.05.2026
        )

        // 3. VR (Excellent for direct URLs, no ciphers)
        val ANDROID_VR get() = YouTubeClient(
            clientName = "ANDROID_VR",
            clientVersion = currentVrVersion,
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/$currentVrVersion (Linux; U; Android 13; en_US; Oculus Quest 3)",
            loginSupported = true,
            useSignatureTimestamp = false
        )

        val ANDROID_VR_NO_AUTH get() = ANDROID_VR.copy(loginSupported = false)



        // used for Lyrics: WEB
        val WEB = YouTubeClient(
            clientName = "WEB",
            clientVersion = "2.20250312.04.00",
            clientId = "1",
            userAgent = USER_AGENT_WEB)

        // Other clients (unused, kept for experiments):

        val MWEB = YouTubeClient(
            clientName = "MWEB",
            // Use a specific legacy version string
            clientVersion = "2.20231201.00.00",
            //clientVersion = "2.20250310.01.00",
            clientId = "2",
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 12_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/12.1.2 Mobile/15E148 Safari/604.1", // MIMIC OLD SAFARI: This is the key to getting direct URLs without Ciphers
            //userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
            loginSupported = true,
            useWebPoTokens = true,
            useSignatureTimestamp = true
        )

        val ANDROID_MUSIC = YouTubeClient(
            clientName = "ANDROID_MUSIC", // Version used by the official YouTube Music app.
            //clientVersion = "9.08.51",
            clientVersion = "6.44.54", // A specific "evergreen" version that is high-trust
            clientId = "16",
            userAgent = "com.google.android.apps.youtube.music/6.44.54 (Linux; U; Android 12; en_US) gzip",
            osName = "Android",
            osVersion = "13",
            androidSdkVersion = 33,
            loginSupported = true,
            useSignatureTimestamp = true, // Official app ALWAYS uses STS
            useWebPoTokens = false
        )

        val ANDROID_CREATOR = YouTubeClient(
            clientName = "ANDROID_CREATOR",
            clientVersion = "24.42.35351",
            clientId = "62",
            userAgent = "com.google.android.apps.youtube.creator/24.42.35351 (Linux; U; Android 14; en_US; Pixel 7; Build/UQ3A.230805.001; Cronet/132.0.6808.3)",
            osName = "Android",
            loginSupported = true,
            useSignatureTimestamp = true,
            useWebPoTokens = false
        )

        val IOS_MUSIC = YouTubeClient(
            clientName = "IOS_MUSIC",
            clientVersion = currentIosVersion,
            clientId = "16", // Note: Using 16 (Music) with an IOS UserAgent is a known "hybrid trust" bypass
            userAgent = "com.google.ios.youtube/$currentIosVersion (iPhone16,2; U; CPU iOS 17_6 like Mac OS X; en_US)",
            loginSupported = true,
            useSignatureTimestamp = true, // High-trust clients usually expect STS
            useWebPoTokens = false
        )

        val TVHTML5 = YouTubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20250303.08.00", // Updated to March 2025 version
            clientId = "7",
            userAgent = "Mozilla/5.0 (SMART-TV; Linux; Tizen 5.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/11.0 TV Safari/537.36",
            loginSupported = true,
            useSignatureTimestamp = false // TV NEVER uses STS/Cipher
        )

        val TVHTML5_SIMPLY_EMBEDDED_PLAYER = YouTubeClient(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER", clientVersion = "2.20240715.01.00", clientId = "85",
            userAgent = "Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.4 Safari/605.1.15",
            loginSupported = true,
            loginRequired = true,
            useSignatureTimestamp = false,
            isEmbedded = true,
        )

        val ANDROID_EMBEDDED_PLAYER = YouTubeClient(
            clientName = "ANDROID_EMBEDDED_PLAYER",
            clientVersion = "19.30.36",
            clientId = "122", // Specific ID for embedded
            userAgent = "com.google.android.youtube/19.30.36 (Linux; U; Android 14) gzip",
            osName = "Android",
            loginSupported = false, // Works best without login
            useSignatureTimestamp = true,
            isEmbedded = true
        )

        val ANDROID_TESTSUITE = YouTubeClient(
            clientName = "ANDROID_TESTSUITE",
            clientVersion = "1.9", // Use a simpler version string
            clientId = "30",
            userAgent = "com.google.android.youtube.testsuite/1.9 (Linux; U; Android 11) gzip",
            loginSupported = false, // TEST: Try false to bypass age restriction via spoofing
            useSignatureTimestamp = false
        )

        // 2026.03.17: Rotate VR version to bypass extraction blocks
        fun rotateVrVersion() {
            val versions = listOf(
                VR_CLIENT_LATEST_VERSION,
                VR_CLIENT_FALLBACK_VERSION,
                VR_CLIENT_ALT1_VERSION,
                VR_CLIENT_ALT2_VERSION
            )
            val currentIndex = versions.indexOf(currentVrVersion)
            // Move to next version, or back to 0 if at end
            currentVrVersion = versions[(currentIndex + 1) % versions.size]
        }



    }
}
