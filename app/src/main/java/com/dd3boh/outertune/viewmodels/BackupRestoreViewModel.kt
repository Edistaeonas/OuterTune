package com.dd3boh.outertune.viewmodels

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.div
import com.dd3boh.outertune.extensions.zipInputStream
import com.dd3boh.outertune.extensions.zipOutputStream
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import javax.inject.Inject
import kotlin.io.buffered
import kotlin.system.exitProcess
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.flow.first
import java.io.File

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    // TODO: make these calls non-blocking
    @ApplicationContext val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    //val TAG = BackupRestoreViewModel::class.simpleName.toString()
    val TAG = "Edgardebug"

    fun backup(uri: Uri, includeLocalInfo: Boolean) {
        runCatching {
            context.applicationContext.contentResolver.openOutputStream(uri)?.use {
                it.buffered().zipOutputStream().use { outputStream ->                    outputStream.setLevel(Deflater.BEST_COMPRESSION)

                    // 1. SETTINGS BACKUP
                    if (includeLocalInfo) {
                        (context.filesDir / "datastore" / SETTINGS_FILENAME).inputStream().buffered().use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                            inputStream.copyTo(outputStream)
                        }
                    } else {
                        Log.i(TAG, "Excluding local file paths from settings.")
                        val tempDsFile = File(context.cacheDir, "temp_backup_settings.preferences_pb")
                        val tempDataStore = PreferenceDataStoreFactory.create(
                            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                            produceFile = { tempDsFile }
                        )

                        val prefsMap = runBlocking { context.dataStore.data.first().asMap() }
                        val keysToExclude = setOf(
                            ScanPathsKey.name,
                            ExcludedScanPathsKey.name,
                            DownloadPathKey.name,
                            DownloadExtraPathKey.name
                        )

                        runBlocking {
                            tempDataStore.edit { tempSettings ->
                                tempSettings.clear()
                                prefsMap.filterKeys { it.name !in keysToExclude }.forEach { (key, value) ->
                                    @Suppress("UNCHECKED_CAST")
                                    tempSettings[key as Preferences.Key<Any>] = value
                                }
                            }
                        }

                        Log.i(TAG, "Checking temp file before copying. Exists: ${tempDsFile.exists()}, Size: ${tempDsFile.length()} bytes.")
                        if (!tempDsFile.exists() || tempDsFile.length() == 0L) {
                            Log.e(TAG, "CRITICAL: Temp file is missing or empty!")
                            // This would cause a failure in the next step.
                        }

                        Log.i(TAG, "Attempting to copy temp file to backup zip...")

                        tempDsFile.inputStream().buffered().use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(SETTINGS_FILENAME))
                            inputStream.copyTo(outputStream)
                        }
                        tempDsFile.delete()
                    }

                    // 2. DATABASE BACKUP
                    runBlocking(Dispatchers.IO) {
                        database.checkpoint()
                    }

                    if (includeLocalInfo) {
                        // Standard database backup
                        FileInputStream(database.openHelper.writableDatabase.path).use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                            inputStream.copyTo(outputStream)
                        }
                    } else {
                        // --- DEFINITIVE FIX: Filter local info from the database ---
                        Log.i(TAG, "Excluding local file paths from database.")
                        val tempDbFile = File(context.cacheDir, "temp_filtered_db.db")

                        // Copy the live database to a temporary file
                        FileInputStream(database.openHelper.writableDatabase.path).use { inputStream ->
                            tempDbFile.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }

                        // Open the temporary database and NULL out the local information
                        SQLiteDatabase.openDatabase(tempDbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                            db.execSQL("UPDATE song SET localPath = NULL, inLibrary = NULL WHERE isLocal = 1")
                            // Optional: Also clean up empty local artists/albums if needed
                        }

                        // Copy the filtered temporary database into the backup zip
                        tempDbFile.inputStream().use { inputStream ->
                            outputStream.putNextEntry(ZipEntry(InternalDatabase.DB_NAME))
                            inputStream.copyTo(outputStream)
                        }
                        tempDbFile.delete()
                    }
                }
            }
        }.onSuccess {
            Toast.makeText(context, R.string.backup_create_success, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.e(TAG, "Backup failed!", it)
            reportException(it)
            Toast.makeText(context, R.string.backup_create_failed, Toast.LENGTH_SHORT).show()
        }
    }

    fun restore(uri: Uri) {
        runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use {
                it.zipInputStream().use { inputStream ->
                    var entry = inputStream.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            SETTINGS_FILENAME -> {
                                (context.filesDir / "datastore" / SETTINGS_FILENAME).outputStream()
                                    .use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                            }

                            InternalDatabase.DB_NAME -> {
                                Log.i(TAG, "Starting database restore")
                                runBlocking(Dispatchers.IO) {
                                    database.checkpoint()
                                }
                                database.close()

                                Log.i(TAG, "Testing new database for compatibility...")
                                val destFile = context.getDatabasePath(InternalDatabase.TEST_DB_NAME)
                                destFile.parentFile?.apply {
                                    if (!exists()) mkdirs()
                                }
                                FileOutputStream(destFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }

                                val status = try {
                                    val t = InternalDatabase.newTestInstance(context, InternalDatabase.TEST_DB_NAME)
                                    t.openHelper.writableDatabase.isDatabaseIntegrityOk
                                    t.close()
                                    true
                                } catch (e: Exception) {
                                    Log.e(TAG, "DB validation failed", e)
                                    false
                                }

                                if (status) {
                                    Log.i(TAG, "Found valid database, proceeding with restore")
                                    destFile.inputStream().use { inputStream ->
                                        FileOutputStream(database.openHelper.writableDatabase.path).use { outputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Incompatible database, aborting restore")
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.err_restore_incompatible_database),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        entry = inputStream.nextEntry
                    }
                }
            }

            val stopIntent = Intent(context, MusicService::class.java)
            context.stopService(stopIntent)
            val startIntent = Intent(context, MainActivity::class.java)
            startIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(startIntent)
            exitProcess(0)
        }.onFailure {
            reportException(it)
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val SETTINGS_FILENAME = "settings.preferences_pb"
    }
}
