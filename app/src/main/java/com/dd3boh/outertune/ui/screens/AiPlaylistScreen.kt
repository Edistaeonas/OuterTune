package com.dd3boh.outertune.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AiApiKeyKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.items.MediaMetadataListItem
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.AiPlaylistViewModel
import com.dd3boh.outertune.utils.AiSong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: AiPlaylistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val event by viewModel.event.collectAsState()
    val playerConnection = LocalPlayerConnection.current
    val snackbarHostState = remember { SnackbarHostState() }
    var prompt by remember { mutableStateOf("") }
    var songCount by remember { mutableFloatStateOf(20f) }
    
    val aiApiKey by rememberPreference(AiApiKeyKey, defaultValue = "")
    var showAiDialog by remember { mutableStateOf(false) }

    var showSaveNameDialog by remember { mutableStateOf<List<MediaMetadata>?>(null) }

    if (showSaveNameDialog != null) {
        val tracks = showSaveNameDialog!!
        TextFieldDialog(
            title = { Text(stringResource(R.string.action_save)) },
            initialTextFieldValue = TextFieldValue(prompt.take(30).trim() + "..."),
            onDone = { name ->
                showSaveNameDialog = null
                viewModel.saveAsPlaylist(name, tracks)
            },
            onDismiss = { showSaveNameDialog = null }
        )
    }

    var showAddToPlaylistDialog by remember { mutableStateOf<List<MediaMetadata>?>(null) }

    if (showAddToPlaylistDialog != null) {
        val tracks = showAddToPlaylistDialog!!
        AddToPlaylistDialog(
            navController = navController,
            songIds = tracks.map { it.id },
            onDismiss = { showAddToPlaylistDialog = null }
        )
    }

    // Logic for PromptLoginOrLocalSave
    var showLoginOrLocalDialog by remember { mutableStateOf<Pair<String, List<MediaMetadata>>?>(null) }

    if (showLoginOrLocalDialog != null) {
        AlertDialog(
            onDismissRequest = { showLoginOrLocalDialog = null },
            title = { Text(stringResource(R.string.ai_playlist_not_logged_in_title)) },
            text = { Text(stringResource(R.string.ai_playlist_not_logged_in_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    val data = showLoginOrLocalDialog!!
                    showLoginOrLocalDialog = null
                    viewModel.saveAsLocalPlaylist(data.first, data.second)
                }) {
                    Text(stringResource(R.string.ai_playlist_save_local))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLoginOrLocalDialog = null
                    navController.navigate("account")
                }) {
                    Text(stringResource(R.string.account))
                }
            }
        )
    }

    // Handle events like showing a snackbar when playlist is saved
    val savedMessage = stringResource(R.string.sync_progress_success)
    LaunchedEffect(event) {
        event?.let {
            when (it) {
                is AiPlaylistViewModel.AiPlaylistEvent.PlaylistSaved -> {
                    snackbarHostState.showSnackbar(savedMessage)
                    viewModel.resetEvent()
                }
                is AiPlaylistViewModel.AiPlaylistEvent.PromptLoginOrLocalSave -> {
                    showLoginOrLocalDialog = it.title to it.tracks
                    viewModel.resetEvent()
                }
            }
        }
    }

    // Standard way in this app to avoid being covered by the bottom player
    val playerAwareInsets = LocalPlayerAwareWindowInsets.current

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { showAiDialog = false },
            title = { Text(stringResource(R.string.ai_api_key_required_title)) },
            text = { Text(stringResource(R.string.ai_api_key_required_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    showAiDialog = false
                    navController.navigate("settings/player")
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_settings_title)) },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        // Single Column with fixed structure:
        // [ Content (Weighted) ]
        // [ Bottom Padding for Player ]
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .windowInsetsPadding(playerAwareInsets) // IMPORTANT: Ensures content is not covered by the player
                .fillMaxSize()
                .imePadding()
                .padding(16.dp)
        ) {
            when (val state = uiState) {
                is AiPlaylistViewModel.AiPlaylistUiState.Idle -> {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(R.string.ai_playlist_prompt_label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 200.dp),
                            placeholder = { Text(stringResource(R.string.ai_playlist_prompt_placeholder)) }
                        )

                        Spacer(Modifier.height(24.dp))

                        Text(
                            text = stringResource(R.string.ai_playlist_song_count) + ": ${songCount.toInt()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Slider(
                            value = songCount,
                            onValueChange = { songCount = it },
                            valueRange = 10f..50f,
                            steps = 39 // Allow every integer from 10 to 50
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { 
                            if (aiApiKey.isEmpty()) {
                                showAiDialog = true
                            } else {
                                viewModel.generatePlaylist(prompt, songCount.toInt())
                            }
                        },
                        enabled = prompt.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_playlist_generate_btn))
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Generating -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.ai_playlist_generating))
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Saving -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.sync_progress_active))
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Suggested -> {
                    Text(
                        text = stringResource(R.string.ai_playlist_suggestions_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(state.songs) { song ->
                            ListItem(
                                headlineContent = { Text(song.title) },
                                supportingContent = { Text(song.artist) }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ai_playlist_retry))
                        }
                        Button(onClick = { viewModel.resolveTracks(state.songs) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ai_playlist_resolve_btn))
                        }
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Resolving -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LinearProgressIndicator(
                            progress = { state.current.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.ai_playlist_resolving, state.current, state.total)
                        )
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Resolved -> {
                    Text(
                        text = stringResource(R.string.ai_playlist_final_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )

                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(state.tracks) { track ->
                            MediaMetadataListItem(
                                mediaMetadata = track,
                                preferredSize = 48,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_back))
                        }
                        Button(
                            onClick = { showSaveNameDialog = state.tracks },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_save), maxLines = 1)
                        }
                        Button(
                            onClick = { showAddToPlaylistDialog = state.tracks },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.ai_playlist_add_existing), maxLines = 1)
                        }
                        Button(
                            onClick = {
                                playerConnection?.playQueue(
                                    ListQueue(
                                        title = prompt.take(30) + "...",
                                        items = state.tracks
                                    )
                                )
                                navController.popBackStack()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.ai_playlist_play_all), maxLines = 1)
                        }
                    }
                }

                is AiPlaylistViewModel.AiPlaylistUiState.Error -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    
                    Button(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ai_playlist_try_again))
                    }
                }
            }
        }
    }
}
