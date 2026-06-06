/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import android.util.Log
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.ENABLE_FFMETADATAEX
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.LookupYtmArtistsKey
import com.dd3boh.outertune.constants.SCANNER_OWNER_LM
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.constants.ScannerOnlyNewFilesKey
import com.dd3boh.outertune.constants.ScannerSensitivityKey
import com.dd3boh.outertune.constants.ScannerStrictExtKey
import com.dd3boh.outertune.constants.ScannerStrictFilePathsKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.constants.ArtistLinkingSensitivity
import com.dd3boh.outertune.constants.ArtistLinkingSensitivityKey
import com.dd3boh.outertune.models.SongTempData
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.ActionPromptDialog
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.destroyScanner
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.getScanner
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerProgressCurrent
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerProgressTotal
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerRequestCancel
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerState
import com.dd3boh.outertune.utils.scanners.ScannerAbortException
import com.dd3boh.outertune.utils.scanners.absoluteFilePathFromUri
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@Composable
fun ColumnScope.LocalScannerFrag() {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val snackbarHostState = LocalSnackbarHostState.current

    // --- DEFINITIVE FIX: Rename the collected state to avoid shadowing ---
    val scannerStateValue by scannerState.collectAsState()
    val scannerProgressTotal by scannerProgressTotal.collectAsState()
    val scannerProgressCurrent by scannerProgressCurrent.collectAsState()

    var scannerFailure by remember { mutableStateOf(false) }
    var mediaPermission by remember { mutableStateOf(true) }

    var showAddFolderDialog: Boolean? by remember {
        mutableStateOf(null)
    }

    // scanner prefs
    val scannerSensitivity by rememberEnumPreference(
        key = ScannerSensitivityKey,
        defaultValue = ScannerMatchCriteria.LEVEL_2
    )
    val scannerImpl by rememberEnumPreference(
        key = ScannerImplKey,
        defaultValue = ScannerImpl.MEDIASTORE
    )
    val strictExtensions by rememberPreference(ScannerStrictExtKey, defaultValue = false)
    val strictFilePaths by rememberPreference(ScannerStrictFilePathsKey, defaultValue = false)
    val downloadPath by rememberPreference(DownloadPathKey, "")
    val (scanPaths, onScanPathsChange) = rememberPreference(ScanPathsKey, defaultValue = "")
    val (excludedScanPaths, onExcludedScanPathsChange) = rememberPreference(
        ExcludedScanPathsKey,
        defaultValue = ""
    )
    val dlPathExtra by rememberPreference(DownloadExtraPathKey, "")

    var fullRescan by remember { mutableStateOf(false) }
    val (lookupYtmArtists, onLookupYtmArtistsChange) = rememberPreference(
        LookupYtmArtistsKey,
        defaultValue = false
    )
    val (onlyScanNew, onOnlyScanNewChange) = rememberPreference(
        ScannerOnlyNewFilesKey,
        defaultValue = false
    )
    val (lastLocalScan, onLastLocalScanChange) = rememberPreference(LastLocalScanKey, 0L)

    LaunchedEffect(onlyScanNew) {
        if (onlyScanNew) {
            // checking this check box should do this on the other 2 checkboxes:
            fullRescan = false
            onLookupYtmArtistsChange(true)
        }
    }
    LaunchedEffect(fullRescan) {
        if (fullRescan) {
            // checking this check box should disable the Only Scan New checkbox:
            onOnlyScanNewChange(false)
        }
    }
    LaunchedEffect(lookupYtmArtists) {
        if (!lookupYtmArtists) {
            // if the youtube linking is unchecked, we should also uncheck the "Only Scan New" checkbox
            onOnlyScanNewChange(false)
        }
    }

    // This is the new, unified scanner function
    fun runScanner(
        isFullRescan: Boolean,
        scanPathsToUse: String,
        excludedScanPathsToUse: String,
        isOnlyScanNew: Boolean
    ) {
        // ... (Permission check logic is unchanged)
        if (context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.scanner_missing_storage_perm),
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
            }
            requestPermissions(
                context as Activity,
                arrayOf(MEDIA_PERMISSION_LEVEL), PackageManager.PERMISSION_GRANTED
            )
            mediaPermission = false
            return
        } else {
            mediaPermission = true
        }

        scannerFailure = false
        playerConnection?.player?.pause()

        playerConnection?.scope?.launch(lmScannerCoroutine) {
            if (scannerState.value in 1..5) {
                return@launch
            }

            try {
                // ... (The main scanning logic inside the try block is unchanged)
                Log.i("runScanner", "Starting main scan task...")
                val scanner = getScanner(context, scannerImpl, SCANNER_OWNER_LM)
                // ... (rest of the try block)
                val processedSongs: ArrayList<SongTempData>
                if (scannerImpl == ScannerImpl.MEDIASTORE) {
                    Log.i("runScanner", "Using MEDIASTORE scanner path.")
                    processedSongs = scanner.fullMediaStoreSync(
                        database,
                        uriListFromString(scanPathsToUse),
                        uriListFromString(excludedScanPathsToUse),
                        scannerSensitivity,
                        strictFilePaths,
                        refreshExisting = isFullRescan,
                        isAutomaticScan = false
                    )
                } else {
                    Log.i("runScanner", "Using file-based scanner path.")
                    val uris = scanner.scanLocal(scanPathsToUse, excludedScanPathsToUse)
                    processedSongs = when {
                        isOnlyScanNew -> {
                            // This is your new "Smart Scan" logic
                            scanner.smartSyncNewFiles(
                                database,
                                uris,
                                scannerSensitivity,
                                strictExtensions,
                                strictFilePaths
                            )
                        }

                        isFullRescan -> {
                            // This is the existing Full Scan
                            scanner.fullSync(
                                database,
                                uris,
                                scannerSensitivity,
                                strictExtensions,
                                strictFilePaths
                            )
                        }

                        else -> {
                            // This is the existing Quick Scan
                            scanner.quickSync(
                                database,
                                uris,
                                scannerSensitivity,
                                strictExtensions,
                                strictFilePaths
                            )
                        }
                    }
                }

                // This bridges the gap between the Sync phase and the Finalize/Link phase.
                scannerState.value = 4

                if (lookupYtmArtists && !scannerRequestCancel) {
                    Log.i("runScanner", "Starting YouTube artist linking task.")
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.scanner_ytm_link_start),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                }

                Log.i("FolderScan", "Main scan complete. Starting finalize/de-duplication step.")
                scanner.finalize(database, processedSongs)
                Log.i(
                    "FolderScan",
                    "De-duplication complete. Starting YouTube artist linking task."
                )

                if (lookupYtmArtists && !scannerRequestCancel) {
                    Log.i("runScanner", "Starting YouTube artist linking background task.")

                    withContext(Dispatchers.IO) {
                        scanner.localToRemoteArtist(database)
                    }
                    Log.i("FolderScan", "YouTube artist linking task complete.")

                    val unlinkedCount = withContext(Dispatchers.IO) {
                        database.getUnlinkedLocalArtistCount()
                    }

                    if (unlinkedCount > 0) {
                        val message = context.resources.getQuantityString(
                            R.plurals.scanner_unlinked_artists_report,
                            unlinkedCount,
                            unlinkedCount
                        )
                        snackbarHostState.showSnackbar(
                            message = message,
                            duration = SnackbarDuration.Indefinite,
                            withDismissAction = true // Add an 'X' or dismiss action
                        )
                        Log.i("FolderScan", message)
                    }
                }

            } catch (e: ScannerAbortException) {
                Log.w("runScanner", "Scanner was aborted by user request.")
            } catch (e: Exception) {
                scannerFailure = true
                reportException(e)
            } finally {
                Log.i("runScanner", "All tasks finished. Cleaning up.")
                val lastScanTime = System.currentTimeMillis()
                onLastLocalScanChange(lastScanTime)

                val message = context.getString(R.string.scanner_finished)
                snackbarHostState.showSnackbar(
                    message = message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Short
                )
                // This now correctly modifies the original MutableStateFlow
                scannerState.value = if (scannerFailure) -1 else 0
                scannerRequestCancel = false
                destroyScanner(SCANNER_OWNER_LM)
                clearDtCache()
            }
        }
    }

    LaunchedEffect(scanPaths) {
        if (scanPaths.isBlank()) {
            showAddFolderDialog = true
        }
    }

    // --- All UI code below now uses scannerStateValue ---
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = {
                // Change: Include state 4 in the "Cancelable" range
                if (scannerStateValue in 1..5) {
                    scannerRequestCancel = true
                    return@Button
                }
                Log.i("runScanner", "Start Full Scan")
                runScanner(fullRescan, scanPaths, excludedScanPaths, onlyScanNew)
            }
        ) {
            Text(
                text = if (scannerStateValue in 1..5) {
                    stringResource(R.string.action_cancel)
                } else if (scannerFailure) {
                    stringResource(R.string.scanner_scan_fail)
                } else if (!mediaPermission) {
                    stringResource(R.string.scanner_missing_storage_perm)
                } else {
                    stringResource(R.string.scanner_btn_idle)
                }
            )
        }

        if (scannerStateValue in 1..5) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = when (scannerStateValue) {
                        1 -> stringResource(R.string.scanner_progress_discovering)
                        3 -> stringResource(R.string.scanner_progress_syncing)
                        4 -> stringResource(R.string.scanner_progress_processing)
                        5 -> stringResource(R.string.scanner_ytm_link_start)
                        else -> stringResource(R.string.scanner_progress_processing)
                    },
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp
                )
                Text(
                    text = if (scannerStateValue == 5) {
                        pluralStringResource(
                            R.plurals.scanner_ytm_link_progress,
                            scannerProgressTotal,
                            scannerProgressCurrent,
                            scannerProgressTotal
                        )
                    } else {
                        "${if (scannerProgressCurrent >= 0) "$scannerProgressCurrent" else "—"}/${
                            if (scannerProgressTotal >= 0) {
                                if (scannerStateValue == 1) {
                                    pluralStringResource(
                                        R.plurals.scanner_n_song_found,
                                        scannerProgressTotal,
                                        scannerProgressTotal
                                    )
                                } else {
                                    pluralStringResource(
                                        R.plurals.scanner_n_song_processed,
                                        scannerProgressTotal,
                                        scannerProgressTotal
                                    )
                                }
                            } else {
                                "—"
                            }
                        }"
                    },
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp
                )
            }
        }
    }
    // ... (rest of the UI code is unchanged)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = onlyScanNew,
                onCheckedChange = onOnlyScanNewChange
            )
            Text(
                stringResource(R.string.scanner_only_new_files),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = fullRescan,
                onCheckedChange = { fullRescan = it }
            )
            Text(
                stringResource(R.string.scanner_variant_rescan),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = lookupYtmArtists,
                onCheckedChange = onLookupYtmArtistsChange,
            )
            Text(
                stringResource(R.string.scanner_online_artist_linking),
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )
        }
    }


    // file path selector
// 1. A real, working Button to configure scan locations.
    Button(        onClick = { showAddFolderDialog = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Text(text = stringResource(R.string.scan_paths_title))
    }

    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )

        Text(
            stringResource(R.string.scanner_warning),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showAddFolderDialog != null) {
        var tempScanPaths = remember { mutableStateListOf<Uri>() }
        LaunchedEffect(showAddFolderDialog, scanPaths, excludedScanPaths) {
            tempScanPaths.clear()
            tempScanPaths.addAll(
                uriListFromString(if (showAddFolderDialog == true) scanPaths else excludedScanPaths)
            )
        }

        ActionPromptDialog(
            titleBar = {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(
                            if (showAddFolderDialog as Boolean) R.string.scan_paths_incl
                            else R.string.scan_paths_excl
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    // switch between include and exclude
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Switch(
                            checked = showAddFolderDialog!!,
                            onCheckedChange = {
                                showAddFolderDialog = !showAddFolderDialog!!
                            },
                        )
                    }
                }
            },
            onDismiss = {
                showAddFolderDialog = null
                tempScanPaths.clear()
            },
//            onConfirm = {
//                val newPathsString = stringFromUriList(tempScanPaths.toList())
//                showAddFolderDialog = null
//                tempScanPaths.clear()
//
//                if (showAddFolderDialog as Boolean) {
//                    onScanPathsChange(newPathsString)
//                    // Run scan with the NEW inclusion paths and OLD exclusion paths
//                    runScanner(isFullRescan = false, scanPathsToUse = newPathsString, excludedScanPathsToUse = excludedScanPaths)
//                    // Run a second time, to make sure...
//                    runScanner(isFullRescan = false, scanPathsToUse = newPathsString, excludedScanPathsToUse = excludedScanPaths)
//                } else {
//                    onExcludedScanPathsChange(newPathsString)
//                    // Run scan with the OLD inclusion paths and NEW exclusion paths
//                    runScanner(isFullRescan = false, scanPathsToUse = scanPaths, excludedScanPathsToUse = newPathsString)
//                    // Run a second time, to make sure...
//                    runScanner(isFullRescan = false, scanPathsToUse = scanPaths, excludedScanPathsToUse = newPathsString)
//                }
//            },

            onConfirm = {
                val newPathsString = stringFromUriList(tempScanPaths.toList())
                val isIncludingFolders = showAddFolderDialog

                showAddFolderDialog = null
                tempScanPaths.clear()

                // --- DEFINITIVE FIX: Implement the user's requested behavior ---
                // Do NOT run a scan automatically. Just save the path.
                if (isIncludingFolders == true) {
                    onScanPathsChange(newPathsString)
                } else if (isIncludingFolders == false) {
                    onExcludedScanPathsChange(newPathsString)
                }

                // set the scan options to default to a full, smart scan.
                fullRescan = true
                onLookupYtmArtistsChange(true)

                // Inform the user what to do next.
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.scanner_manual_scan_advice),
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite
                    )
                }
                // --- END FIX ---
            },

            onReset = {
                // clear all, let user select a new path on their own will
                tempScanPaths.clear()
            },
            onCancel = {
                showAddFolderDialog = null
                tempScanPaths.clear()
            },
            isInputValid = tempScanPaths.toList().all {
                // scan path cannot be the download directory or subdir of download directory
                !it.toString().contains(uriListFromString(downloadPath).firstOrNull().toString())
                        && uriListFromString(dlPathExtra).none { f ->
                    it.toString().contains(f.toString())
                }
            } || tempScanPaths.isEmpty()
        ) {
            val scrollState = rememberScrollState()
            val dirPickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                if (tempScanPaths.any { it.toString() == uri.toString() }) return@rememberLauncherForActivityResult
                val contentResolver = context.contentResolver
                val takeFlags: Int =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                tempScanPaths.add(uri)
            }
            Text(
                text = stringResource(R.string.scan_paths_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.padding(vertical = 8.dp))

            // folders list
            Column(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .border(
                        2.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(ThumbnailCornerRadius)
                    )
                    .heightIn(max = 280.dp) // Add this line to limit the height
                    .verticalScroll(scrollState) // Add this line to make it scroll
            ) {
                tempScanPaths.forEach {
                    val valid = !it.toString()
                        .contains(uriListFromString(downloadPath).firstOrNull().toString())
                            && uriListFromString(dlPathExtra).none { f ->
                        it.toString().contains(f.toString())
                    }
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .background(if (valid) Color.Transparent else MaterialTheme.colorScheme.errorContainer)
                            .clickable { }) {
                        Text(
                            text = absoluteFilePathFromUri(context, it) ?: it.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                        )
                        IconButton(
                            onClick = {
                                tempScanPaths.remove(it)
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }

            // add folder button
            Column {
                Button(onClick = { dirPickerLauncher.launch(null) }) {
                    Text(stringResource(R.string.scan_paths_add_folder))
                }
                InfoLabel(
                    text = stringResource(R.string.scan_paths_tooltip),
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (tempScanPaths.toList().any {
                        it.toString() == uriListFromString(downloadPath).firstOrNull().toString()
                    }) {
                    InfoLabel(
                        text = stringResource(R.string.scanner_rejected_dir),
                        isError = true,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}



@Composable
fun ColumnScope.LocalScannerExtraFrag() {
    val context = LocalContext.current

    val (scannerSensitivity, onScannerSensitivityChange) = rememberEnumPreference(
        key = ScannerSensitivityKey,
        defaultValue = ScannerMatchCriteria.LEVEL_2
    )
    val (scannerImpl, onScannerImplChange) = rememberEnumPreference(
        key = ScannerImplKey,
        defaultValue = ScannerImpl.MEDIASTORE
    )
    val (strictExtensions, onStrictExtensionsChange) = rememberPreference(ScannerStrictExtKey, defaultValue = false)
    val (strictFilePaths, onStrictFilePathsChange) = rememberPreference(ScannerStrictFilePathsKey, defaultValue = false)

    val (artistLinkingSensitivity, onArtistLinkingSensitivityChange) = rememberEnumPreference(
        key = ArtistLinkingSensitivityKey,
        defaultValue = ArtistLinkingSensitivity.COMPLEX
    )

// Artist linking sensitivity
    EnumListPreference(
        title = { Text(stringResource(R.string.artist_linking_sensitivity_title)) },
        icon = { Icon(Icons.Rounded.Interests, null) }, // You can change the icon if preferred
        selectedValue = artistLinkingSensitivity,
        onValueSelected = onArtistLinkingSensitivityChange,
        valueText = {
            when (it) {
                ArtistLinkingSensitivity.SIMPLE -> stringResource(R.string.artist_linking_simple)
                ArtistLinkingSensitivity.COMPLEX -> stringResource(R.string.artist_linking_complex)
            }
        }
    )
    InfoLabel(
        text = when (artistLinkingSensitivity) {
            ArtistLinkingSensitivity.SIMPLE -> stringResource(R.string.artist_linking_simple_description)
            ArtistLinkingSensitivity.COMPLEX -> stringResource(R.string.artist_linking_complex_description)
        }
    )

    // scanner sensitivity
    EnumListPreference(
        title = { Text(stringResource(R.string.scanner_sensitivity_title)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = scannerSensitivity,
        onValueSelected = onScannerSensitivityChange,
        valueText = {
            when (it) {
                ScannerMatchCriteria.LEVEL_1 -> stringResource(R.string.scanner_sensitivity_L1)
                ScannerMatchCriteria.LEVEL_2 -> stringResource(R.string.scanner_sensitivity_L2)
                ScannerMatchCriteria.LEVEL_3 -> stringResource(R.string.scanner_sensitivity_L3)
            }
        },
        isEnabled = !strictFilePaths,
    )
    // strict file ext
    SwitchPreference(
        title = { Text(stringResource(R.string.scanner_strict_file_name_title)) },
        description = stringResource(R.string.scanner_strict_file_name_description),
        icon = { Icon(Icons.Rounded.TextFields, null) },
        isEnabled = !strictFilePaths,
        checked = strictExtensions,
        onCheckedChange = onStrictExtensionsChange
    )
    // compare file path only
    SwitchPreference(
        title = { Text(stringResource(R.string.scanner_strict_file_paths_title)) },
        description = stringResource(R.string.scanner_strict_file_paths_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = strictFilePaths,
        onCheckedChange = onStrictFilePathsChange,
    )
    // scanner type
    EnumListPreference(
        title = { Text(stringResource(R.string.scanner_type_title)) },
        icon = { Icon(Icons.Rounded.Speed, null) },
        selectedValue = scannerImpl,
        onValueSelected = onScannerImplChange,
        valueText = {
            when (it) {
                ScannerImpl.MEDIASTORE -> stringResource(R.string.scanner_type_mediastore)
                ScannerImpl.TAGLIB -> stringResource(R.string.scanner_type_taglib)
                ScannerImpl.FFMPEG_EXT -> stringResource(R.string.scanner_type_ffmpeg_ext)
            }
        },
        disabled = { it == ScannerImpl.FFMPEG_EXT && !ENABLE_FFMETADATAEX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R },
        values = ScannerImpl.entries,
    )
    InfoLabel(stringResource(R.string.scanner_type_tooltip))
}

