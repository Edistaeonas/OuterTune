package com.dd3boh.outertune.utils

import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import java.math.BigInteger
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlin.math.absoluteValue
import java.text.Normalizer

/*
IMPORTANT: Put any string utils that require composable in outertune/ui/utils/StringUtils.kt
 */

// --- NORMALIZATION FUNCTION ---
internal fun String.normalizeForMatching(): String {
    // 1. Remove accents and convert to lowercase
    var normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()

    // 2. Ignore articles "The", "Les", "Las", "Los" at the beginning of the string
    normalized = normalized.replace(Regex("^\\b(the|les|las|los)\\b\\s*"), "")

    // 3. Replace "&" and "+" with "and"
    normalized = normalized.replace("&", "and").replace("+", "and")

    // 4. Remove punctuation
    // We allow letters, numbers, and $.
    normalized = normalized.replace(Regex("[^a-z0-9$]"), "")

    return normalized.trim()
}

/**
 * Removes text within parentheses and brackets, then normalizes.
 * Useful for "Title (Remastered)" -> "Title"
 */
fun String.cleanTitle(): String {
    return this.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").normalizeForMatching()
}


fun makeTimeString(duration: Long?): String {
    if (duration == null || duration < 0) return ""
    var sec = duration / 1000
    val day = sec / 86400
    sec %= 86400
    val hour = sec / 3600
    sec %= 3600
    val minute = sec / 60
    sec %= 60
    return when {
        day > 0 -> "%d:%02d:%02d:%02d".format(day, hour, minute, sec)
        hour > 0 -> "%d:%02d:%02d".format(hour, minute, sec)
        else -> "%d:%02d".format(minute, sec)
    }
}

fun md5(str: String): String {
    val md = MessageDigest.getInstance("MD5")
    return BigInteger(1, md.digest(str.toByteArray())).toString(16).padStart(32, '0')
}

fun joinByBullet(vararg str: String?) =
    str.filterNot {
        it.isNullOrEmpty()
    }.joinToString(separator = " • ")

fun String.urlEncode(): String = Uri.encode(this)

fun fixFilePath(path: String) = "/" + path.split('/').filterNot { it.isEmpty() }.joinToString("/")

fun formatFileSize(sizeBytes: Long): String {
    val prefix = if (sizeBytes < 0) "-" else ""
    var result: Long = sizeBytes.absoluteValue
    var suffix = "B"
    if (result > 900) {
        suffix = "KB"
        result /= 1024
    }
    if (result > 900) {
        suffix = "MB"
        result /= 1024
    }
    if (result > 900) {
        suffix = "GB"
        result /= 1024
    }
    if (result > 900) {
        suffix = "TB"
        result /= 1024
    }
    if (result > 900) {
        suffix = "PB"
        result /= 1024
    }
    return "$prefix$result $suffix"
}

/*
 * Whacko methods
 */

/**
 * Find the matching string, if not found the closest super string
 */
fun closestMatch(query: String, stringList: List<ArtistEntity>): ArtistEntity? {
    val normalizedQuery = query.normalizeForMatching()

    // --- Use ONLY an exact match on the normalized names ---

    val exactNormalizedMatch = stringList.find { it.name.normalizeForMatching() == normalizedQuery }

    if (exactNormalizedMatch != null) {
        Log.i("ArtistMatchDebug", "NORMALIZED EXACT MATCH: Query '$query' matched to '${exactNormalizedMatch.name}'")
    } else {
        Log.w("ArtistMatchDebug", "NO MATCH: No exact normalized match could be found for artist query: '$query'")
    }

    return exactNormalizedMatch

}

/**
 * Find the matching string, if not found the closest super string
 */
fun closestAlbumMatch(query: String, stringList: List<AlbumEntity>): AlbumEntity? {
    // Check for exact match first

    val exactMatch = stringList.find { query.equals(it.title, true) }
    if (exactMatch != null) {
        return exactMatch
    }

    // Check for query as substring in any of the strings
    val substringMatches = stringList.filter { it.title.contains(query) }
    if (substringMatches.isNotEmpty()) {
        return substringMatches.minByOrNull { it.title.length }
    }

    return null
}

/**
 * Convert a number to a string representation
 *
 * The value of the number is denoted with characters from A through J. A being 0, and J being 9. This is prefixed by
 * number of digits the number has (always 2 digits, in the same representation) and succeeded with a null terminator "0"
 * In format:
 * <digit tens><digit ones><value in string form>0
 *
 *
 * For example:
 * 100          -> ADBAA0 ("AD" is "03", which represents this is a AD 3 digit number, "BAA" is "100")
 * 101          -> ADBAB0
 * 1013         -> AEBABD0
 * 9            -> ABJ0
 * 111222333444 -> BCBBBCCCDDDEEE0
 */
fun numberToAlpha(l: Long): String {
    val alphabetMap = ('A'..'J').toList()
    val weh = if (l < 0) "0" else l.toString()
    val lengthStr = if (weh.length.toInt() < 10) {
        "0" + weh.length.toInt()
    } else {
        weh.length.toInt().toString()
    }

    return (lengthStr + weh + "\u0000").map {
        if (it == '\u0000') {
            "0"
        } else {
            alphabetMap[it.digitToInt()]
        }
    }.joinToString("")
}

/**
 * Compare two version‐strings, returning:
 *   > 0 if v1 > v2
 *   < 0 if v1 < v2
 *   = 0 if they’re considered equal
 */
fun compareVersion(v1: String, v2: String): Int {
    fun normalize(v: String) = v.substringBefore('-')
    val parts1 = normalize(v1).split('.').map { it.toIntOrNull() ?: 0 }
    val parts2 = normalize(v2).split('.').map { it.toIntOrNull() ?: 0 }

    val max = maxOf(parts1.size, parts2.size)
    for (i in 0 until max) {
        val n1 = parts1.getOrElse(i) { 0 }
        val n2 = parts2.getOrElse(i) { 0 }
        if (n1 != n2) return n1 - n2
    }
    return 0
}

object MojibakeFixer {
    // Typical patterns of UTF-8 text incorrectly decoded as Windows-1252/ISO-8859-1 (mojibake)
    private val mojibakePatterns = listOf(
        "Ã[\\u0080-\\u00BF]", // Catch-all for common 2-byte UTF-8 mojibake
        "Å[\\u0080-\\u00BF]", // Specifically catches œ/Œ patterns
        "â[\\u0080-\\u00BF]{2}", // Specifically catches smart quotes/dashes
        "â\uFFFD\uFFFD",         // Mangled replacement sequences
        "\uFFFD"                 // The replacement character itself (U+FFFD)
    ).map { it.toRegex() }

    private fun looksLikeMojibake(s: String): Boolean {
        val result = mojibakePatterns.any { it.containsMatchIn(s) }
        // We log the input and the detection result
        //Log.i("Edgardebug", "looksLikeMojibake: '$s' -> detected=$result")
        return result
    }
    /**
     * Re-decodes a string by treating it as Windows-1252 bytes and interpreting them as UTF-8.
     * Windows-1252 is preferred over ISO-8859-1 as it covers the 0x80-0x9F range where œ lives.
     */
    private fun tryReDecode(s: String): String {
        return try {
            val bytes = s.toByteArray(Charset.forName("Windows-1252"))
            val decoded = String(bytes, Charsets.UTF_8)

            // If the re-decoded version has significantly more replacement characters, it failed.
            if (decoded.count { it == '\uFFFD' } > s.count { it == '\uFFFD' } + 1) s else decoded
        } catch (e: Exception) {
            s
        }
    }

    // Clean up invisible or tricky characters (NBSP, zero-width chars, BOM)
    private fun cleanInvisible(s: String): String = s
        .replace("\u00A0", " ")
        .replace("\u200B", "")
        .replace("\uFEFF", "")

    // Unicode normalization (recommended NFC form for storage and display)
    private fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /**
     * Main repair function.
     */
    fun fix(input: String): String {
        if (input.isBlank()) return input
        var s = input

        // 1. Double-check for the specific 'œ' mojibake Å“ (\u00C5\u201C)
        s = s.replace("\u00C5\u201C", "oe")
            .replace("\u00C5\u2019", "Oe")

        // 2. Heuristic re-decoding
        if (looksLikeMojibake(s)) {
            s = tryReDecode(s)
        }

        // 3. Cleanup and NFC Normalization (preserves é, ç, etc.)
        s = cleanInvisible(s)
        s = normalize(s)

        // 4. Expand only the requested ligatures
        val result = s.replace("œ", "oe").replace("Œ", "Oe")
            .replace("æ", "ae").replace("Æ", "Ae")
            .replace("ß", "ss")

        if (input != result) {
            Log.i("Edgardebug", "MojibakeFixer.fix final: '$input' -> '$result'")
        }
        return result
    }
}

/**
 * Global metadata sanitizer using MojibakeFixer
 */
fun sanitizeMetadata(input: String): String = MojibakeFixer.fix(input)


/**
 * Converts a string to Start Case (Title Case) with smart rules for punctuation and capitalization.
 *
 * - Tokenizes words based on alphanumeric characters (including Unicode letters) and apostrophes.
 * - Capitalizes the first letter of each word.
 * - Preserves existing internal capitals in mixed-case words (e.g., "McCartney", "iPhone").
 * - Keeps Roman Numerals in full uppercase (excluding the word "mix").
 * - Keeps acronyms (2-5 characters, all uppercase) in full uppercase if the rest of the string is not all-caps.
 * - If the entire input is all-caps, converts everything to Start Case (except whitelist/Roman numerals).
 */
fun convertToStartCase(input: String): String {
    val sanitized = sanitizeMetadata(input)
    val romanRegex = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$", RegexOption.IGNORE_CASE)
    val apostrophes = setOf('\'', '’', '‘', '´', '`')
    val alwaysUppercase = setOf("EP", "LP", "USA", "USSR", "NY", "UK", "CD", "DVD", "DJ", "MC", "NASA",
        "SOS", "MIA", "RIP", "OMG", "YMCA", "IOU", "DIY", "POV", "BRB")

    // Check if the entire input is shouting (all-caps). Only count letters.
    val letters = sanitized.filter { it.isLetter() }
    val inputIsAllUpper = letters.isNotEmpty() && letters.all { it.isUpperCase() }

    // This regex identifies words: Unicode letters (\p{L}), digits, and various apostrophes
    val wordRegex = Regex("[\\p{L}0-9'’‘´`]+")
    val result = StringBuilder()
    var lastIndex = 0

    wordRegex.findAll(sanitized).forEach { match ->
        // 1. Append the separator (punctuation/spaces) that appeared before this word
        result.append(sanitized.substring(lastIndex, match.range.first))

        val word = match.value
        // wordOnly for Roman/Acronym checks: keep only the core alphanumeric part
        val wordOnly = word.trim { it in apostrophes }
        val wordOnlyUpper = wordOnly.uppercase()

        val processedWord = when {
            // Whitelist Check (always uppercase)
            wordOnlyUpper in alwaysUppercase -> word.uppercase()

            // Roman Numeral check (ignore "mix")
            wordOnly.isNotEmpty() && !wordOnly.equals("mix", ignoreCase = true) && romanRegex.matches(wordOnly) -> word.uppercase()

            // Acronym Check: Keep 2-5 letter all-caps words as they are if input isn't shouting.
            !inputIsAllUpper && word.all { !it.isLetter() || it.isUpperCase() } && word.any { it.isLetter() } && wordOnly.length in 2..5 -> word.uppercase()

            // Standard Start Case processing
            else -> {
                val isWordAllUpper = word.all { !it.isLetter() || it.isUpperCase() }
                val isMixedCase = !isWordAllUpper && word.any { it.isLowerCase() }
                val wordResult = StringBuilder()
                
                // Use character-by-character approach for perfect Unicode and apostrophe handling
                var capitalizeNext = word[0] !in apostrophes

                for (i in word.indices) {
                    val char = word[i]
                    if (Character.isLetter(char)) {
                        if (capitalizeNext) {
                            wordResult.append(char.uppercaseChar())
                            capitalizeNext = false
                        } else if (char.isUpperCase() && isMixedCase) {
                            wordResult.append(char) // McCartney/O'Connor rule
                        } else {
                            wordResult.append(char.lowercaseChar())
                        }
                    } else {
                        wordResult.append(char)
                        // Apostrophes and digits within a token do NOT trigger capitalization
                        if (char in apostrophes || Character.isDigit(char)) {
                            capitalizeNext = false
                        } else if (!Character.isLetterOrDigit(char)) {
                            capitalizeNext = true
                        }
                    }
                }
                wordResult.toString()
            }
        }

        result.append(processedWord)
        lastIndex = match.range.last + 1
    }

    // 2. Append any remaining characters at the end
    if (lastIndex < sanitized.length) {
        result.append(sanitized.substring(lastIndex))
    }

    val converted = result.toString()
    if (input != converted) {
        Log.d("Edgardebug", "convertToStartCase: '$input' -> '$converted'")
    }
    return converted
}
