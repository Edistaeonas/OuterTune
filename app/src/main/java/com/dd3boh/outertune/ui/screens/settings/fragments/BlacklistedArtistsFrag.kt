package com.dd3boh.outertune.ui.screens.settings.fragments

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.BlacklistedArtistEntity
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ColumnScope.BlacklistedArtistsFrag() {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val blacklistedArtists by database.getAllBlacklistedArtists().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            val content = blacklistedArtists.joinToString("\n") { it.name }
                            outputStream.write(content.toByteArray())
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.blacklist_exported, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(it)?.use { inputStream ->
                            val names = inputStream.bufferedReader().readLines()
                            names.filter { it.isNotBlank() }.forEach { name ->
                                database.insert(BlacklistedArtistEntity(name.trim()))
                            }
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.blacklist_imported, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.blacklisted_artists)) },
        description = stringResource(R.string.blacklisted_artists_description),
        icon = { Icon(Icons.Rounded.Block, null) },
        onClick = { showAddDialog = true }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = { importLauncher.launch(arrayOf("text/plain")) },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.import_blacklist))
        }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { exportLauncher.launch("blacklist_artists.txt") },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Rounded.Upload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_blacklist))
        }
    }

    blacklistedArtists.forEach { artist ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = artist.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.delete(artist)
                    }
                }
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
            }
        }
    }

    if (showAddDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.add_blacklisted_artist)) },
            placeholder = { Text(stringResource(R.string.artist_name_placeholder)) },
            onDismiss = { showAddDialog = false },
            onDone = { name ->
                if (name.isNotBlank()) {
                    coroutineScope.launch(Dispatchers.IO) {
                        database.insert(BlacklistedArtistEntity(name.trim()))
                    }
                }
                showAddDialog = false
            }
        )
    }
}
