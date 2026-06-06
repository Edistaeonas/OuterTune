package com.dd3boh.outertune.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortDescendingKey
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.constants.PlaylistSortTypeKey
import com.dd3boh.outertune.constants.SyncMode
import com.dd3boh.outertune.constants.YtmSyncModeKey
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.ui.component.SortHeader
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.PlaylistListItem
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AddToPlaylistDialog(
    navController: NavController,
    allowSyncing: Boolean = true,
    initialTextFieldValue: String? = null,
    songIds: List<String>?, // song ids to insert.
    onPreAdd: (suspend (Playlist) -> List<String>)? = null,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val (sortType, onSortTypeChange) = rememberEnumPreference(PlaylistSortTypeKey, PlaylistSortType.CREATE_DATE)
    val (sortDescending, onSortDescendingChange) = rememberPreference(PlaylistSortDescendingKey, true)
    val syncMode by rememberEnumPreference(key = YtmSyncModeKey, defaultValue = SyncMode.RW)

    var playlists by remember {
        mutableStateOf(emptyList<Playlist>())
    }
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    // Use remember for selection state. SnapshotStateList is fine for recomposition.
    val selectedPlaylists = remember { mutableStateListOf<Playlist>() }
    var songIdsState by remember { mutableStateOf(songIds) }

    LaunchedEffect(Unit) {
        if (syncMode == SyncMode.RO) {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 1).collect {
                playlists = it
            }
        } else {
            database.playlists(PlaylistFilter.LIBRARY, sortType, sortDescending, 2).collect {
                playlists = it
            }
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        if (selectedPlaylists.isEmpty()) {
                            withContext(Dispatchers.Main) { onDismiss() }
                            return@launch
                        }

                        var totalAdded = 0
                        val firstPlaylistName = selectedPlaylists.firstOrNull()?.playlist?.name ?: ""
                        val numPlaylists = selectedPlaylists.size

                        selectedPlaylists.forEach { playlist ->
                            // Use the state songIds if available, otherwise original
                            val currentSongIds = songIdsState ?: return@forEach

                            val duplicates = database.playlistDuplicates(playlist.id, currentSongIds)
                            val songsToAdd = currentSongIds.filter { !duplicates.contains(it) }

                            if (songsToAdd.isNotEmpty()) {
                                database.addSongToPlaylist(playlist, songsToAdd)
                                totalAdded += songsToAdd.size

                                if (!playlist.playlist.isLocal) {
                                    playlist.playlist.browseId?.let { plist ->
                                        YouTube.addSongsToPlaylist(plist, songsToAdd)
                                    }
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            if (totalAdded > 0) {
                                val message = if (numPlaylists == 1) {
                                    context.getString(R.string.ai_playlist_added_to_existing, totalAdded, firstPlaylistName)
                                } else {
                                    context.getString(R.string.ai_playlist_added_to_multiple, totalAdded, numPlaylists)
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                            onDismiss()
                        }
                    }
                },
                enabled = selectedPlaylists.isNotEmpty()
            ) {
                Text(stringResource(R.string.add))
            }
        }
    ) {
        Column(modifier = Modifier.height(400.dp)) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    ListItem(
                        title = stringResource(R.string.create_playlist),
                        thumbnailContent = {
                            Image(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                                modifier = Modifier.size(ListThumbnailSize)
                            )
                        },
                        modifier = Modifier.clickable {
                            showCreatePlaylistDialog = true
                        }
                    )
                }

                item {
                    InfoLabel(
                        text = stringResource(R.string.playlist_add_local_to_synced_note),
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                item {
                    SortHeader(
                        sortType = sortType,
                        sortDescending = sortDescending,
                        onSortTypeChange = onSortTypeChange,
                        onSortDescendingChange = onSortDescendingChange,
                        sortTypeText = { sortType ->
                            when (sortType) {
                                PlaylistSortType.CREATE_DATE -> R.string.sort_by_create_date
                                PlaylistSortType.NAME -> R.string.sort_by_name
                                PlaylistSortType.SONG_COUNT -> R.string.sort_by_song_count
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                items(playlists) { playlist ->
                    val isSelected = selectedPlaylists.any { it.id == playlist.id }
                    
                    PlaylistListItem(
                        playlist = playlist,
                        trailingContent = {
                            Icon(
                                imageVector = if (isSelected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        },
                        modifier = Modifier.clickable {
                            if (isSelected) {
                                selectedPlaylists.removeAll { it.id == playlist.id }
                            } else {
                                selectedPlaylists.add(playlist)
                                
                                if (onPreAdd != null) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val result = onPreAdd(playlist)
                                        if (songIdsState == null) {
                                            songIdsState = result
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                if (syncMode == SyncMode.RO) {
                    item {
                        TextButton(
                            onClick = {
                                navController.navigate("settings/account_sync")
                                onDismiss()
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.playlist_missing_note),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = TextUnit(12F, TextUnitType.Sp),
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
            allowSyncing = allowSyncing
        )
    }
}
