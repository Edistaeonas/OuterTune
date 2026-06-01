package com.dd3boh.outertune.playback

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess
import com.dd3boh.outertune.R
import com.dd3boh.outertune.utils.dataStore

class ForegroundRestartService : Service() {
    private val TAG = "ForegroundRestartService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val autoPlay = intent?.getBooleanExtra("autoPlay", false) ?: false
        Log.i(TAG, "onStartCommand: restart requested autoPlay=$autoPlay")

        // Create NotificationChannel and Notification if needed (same CHANNEL_ID used in MusicService)
        try {
            ensureNotificationChannel()
            val notif = NotificationCompat.Builder(this, MusicService.CHANNEL_ID)
                .setContentTitle(getString(R.string.music_player))
                .setContentText("Restarting app...")
                .setSmallIcon(R.drawable.small_icon)
                .setCategory(android.app.Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            startForeground(MusicService.NOTIFICATION_ID, notif)
            Log.i(TAG, "startForeground posted")
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            // continue anyway — we'll still try the launch methods
        }

        // Perform the same robust launch attempts (startActivity, pendingIntent.send, alarm fallback)
        runBlocking {
            withContext(Dispatchers.Default) {
                try {
                    performRestartAttempts(autoPlay)
                } catch (e: Exception) {
                    Log.w(TAG, "performRestartAttempts failed", e)
                }
            }
            // Let the system hand over to new Activity; wait briefly then stop
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            stopForeground(true)
            stopSelf()
            // allow process to exit cleanly if desired
            try { exitProcess(0) } catch (_: Throwable) {}
        }

        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        // Delegate to MusicService's helper if you have one. Minimal safe creation:
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(MusicService.CHANNEL_ID)
                ?: android.app.NotificationChannel(MusicService.CHANNEL_ID, MusicService.CHANNEL_NAME, android.app.NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Foreground restart helper"
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                }
            nm.createNotificationChannel(channel)
        }
    }

    private fun performRestartAttempts(autoPlay: Boolean) {
        val pmIntent = packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "No launch intent, abort")
            return
        }
        val component = pmIntent.component
        val restartIntent = Intent.makeRestartActivityTask(component).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("expandPlayer", autoPlay)
        }

        // persist autoplay using a local preferences key (do NOT instantiate MusicService)
        try {
            val AutoPlayRestartKey = androidx.datastore.preferences.core.booleanPreferencesKey("auto_play_restart")
            runBlocking { dataStore.edit { it[AutoPlayRestartKey] = autoPlay } }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist autoplay", e)
        }

        // Try startActivity
        try {
            startActivity(restartIntent)
            Log.i(TAG, "performRestartAttempts: startActivity succeeded")
            return
        } catch (e: Exception) {
            Log.w(TAG, "performRestartAttempts: startActivity failed", e)
        }

        // Try PendingIntent.send
        val restartPI = PendingIntent.getActivity(this, 0, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            restartPI.send()
            Log.i(TAG, "performRestartAttempts: pendingIntent.send succeeded")
            return
        } catch (e: Exception) {
            Log.w(TAG, "performRestartAttempts: pendingIntent.send failed", e)
        }

        // Alarm fallback
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val triggerAt = System.currentTimeMillis() + 5000L
            alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, restartPI)
            Log.i(TAG, "performRestartAttempts: scheduled alarm fallback at $triggerAt")
        } catch (e: Exception) {
            Log.w(TAG, "performRestartAttempts: alarm scheduling failed", e)
        }
    }
}
