package com.dd3boh.outertune.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

object LogCollector {
    private var process: Process? = null
    private var currentLevel: String? = null // Changed to nullable to track initial state correctly

    fun start(context: Context, level: String = "I") {
        // Fix: Avoid redundant restarts if already running with the same level
        if (process != null && currentLevel == level) return

        currentLevel = level
        stop()

        val logFile = File(context.getExternalFilesDir(null), "outertune_logs.txt")

        // Fix: If we are appending to an existing file, use -T 1 to avoid
        // dumping the logcat buffer again, which causes duplicates.
        val skipBuffer = logFile.exists() && logFile.length() > 0

        try {
            val logcatArgs = if (skipBuffer) "-v time -T 1" else "-v time"
            val command = "logcat $logcatArgs *:$level >> ${logFile.absolutePath}"
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        } catch (e: IOException) {
            Log.e("LogCollector", "Failed to start logcat", e)
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    fun exportLog(context: Context, targetDirectoryUri: Uri): Boolean {
        val logFile = File(context.getExternalFilesDir(null), "outertune_logs.txt")
        if (!logFile.exists()) return false

        val wasRunning = process != null
        val level = currentLevel ?: "I"

        // Stop current collection to release file handle
        stop()

        return try {
            val directory = DocumentFile.fromTreeUri(context, targetDirectoryUri) ?: return false
            val fileName = "outertune_logs_${System.currentTimeMillis()}.txt"
            val newFile = directory.createFile("text/plain", fileName) ?: return false

            // COPY LOG FILE TO NEW LOCATION
            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                logFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            // DELETE ORIGINAL FILE
            logFile.delete()

            // Clear the system log buffer to prevent duplicate logs on restart.
            // Note: This may not work on all Android versions/devices without specific permissions,
            // but the -T 1 flag in start() provides a secondary defense against duplication.
            try {
                Runtime.getRuntime().exec("logcat -c").waitFor()
            } catch (e: Exception) {
                // Ignore failure
            }

            // Restart collection if it was active
            if (wasRunning) start(context, level)
            true
        } catch (e: Exception) {
            Log.e("LogCollector", "Log export failed", e)
            // Try to recover even on failure
            if (wasRunning) start(context, level)
            false
        }
    }
}