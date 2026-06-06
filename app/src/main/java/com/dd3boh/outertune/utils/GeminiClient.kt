package com.dd3boh.outertune.utils

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent
)

@Serializable
data class AiSong(
    val title: String,
    val artist: String
)

object GeminiClient {
    private const val TAG = "GeminiClient"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-3.5-flash:generateContent"

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 15000
            socketTimeoutMillis = 60000
        }
    }

    suspend fun generatePlaylist(apiKey: String, prompt: String, songCount: Int = 20): Result<List<AiSong>> {
        if (apiKey.isEmpty()) return Result.failure(Exception("API Key is missing"))

        val systemPrompt = """
            You are a professional music curator. Based on the user's criteria, generate a playlist of exactly $songCount songs.
            Return ONLY a raw JSON array of objects. Each object must have "title" and "artist" keys.
            Do not include Markdown formatting, code blocks, or any text before or after the JSON array.
            
            Example output format:
            [{"title": "Song Name", "artist": "Artist Name"}, ...]
        """.trimIndent()

        val fullPrompt = "$systemPrompt\n\nUser criteria: $prompt"

        return runCatching {
            val response = client.post(BASE_URL) {
                parameter("key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(GeminiRequest(
                    contents = listOf(GeminiContent(listOf(GeminiPart(fullPrompt))))
                ))
            }

            if (!response.status.isSuccess()) {
                val errorBody = response.bodyAsText()
                Log.e(TAG, "API Error (${response.status}): $errorBody")
                throw Exception("AI API Error: ${response.status.value}. Detail: $errorBody")
            }

            val resultObj = response.body<GeminiResponse>()
            val responseText = resultObj.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (responseText == null) {
                val rawBody = response.bodyAsText()
                Log.e(TAG, "Empty candidates in response. Raw body: $rawBody")
                throw Exception("AI returned an empty response. This may be due to safety filters or region restrictions.")
            }

            Log.d(TAG, "AI Response: $responseText")

            // Parse the JSON array from the response text
            // Sometimes AI might still include some junk or backticks
            val cleanJson = responseText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            
            Json { ignoreUnknownKeys = true }.decodeFromString<List<AiSong>>(cleanJson)
        }.onFailure {
            Log.e(TAG, "Failed to generate playlist", it)
        }
    }
}
