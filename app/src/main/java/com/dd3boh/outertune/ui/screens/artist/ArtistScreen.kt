/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.artist

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.playback.queues.YouTubeQueue
import com.dd3boh.outertune.ui.component.AutoResizeText
import com.dd3boh.outertune.ui.component.FontSizeRange
import com.dd3boh.outertune.ui.component.HideOnScrollFAB
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.items.YouTubeGridItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.dd3boh.outertune.ui.component.shimmer.ArtistPagePlaceholder
import com.dd3boh.outertune.ui.menu.AlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeAlbumMenu
import com.dd3boh.outertune.ui.menu.YouTubeArtistMenu
import com.dd3boh.outertune.ui.menu.YouTubePlaylistMenu
import com.dd3boh.outertune.ui.menu.YouTubeSongMenu
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.ui.utils.resize
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.ArtistViewModel
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val density = LocalDensity.current
    val menuState = LocalMenuState.current
    val coroutineScope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current ?: return

    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    var showManualLinkDialog by rememberSaveable { mutableStateOf(false) }
    var showUnlinkConfirmDialog by rememberSaveable { mutableStateOf(false) }

    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val artistPage = viewModel.artistPage
    val libraryArtist by viewModel.libraryArtist.collectAsState()
    val librarySongs by viewModel.librarySongs.collectAsState()
    val libraryAlbums by viewModel.libraryAlbums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val lazyListState = rememberLazyListState()
    val snackbarHostState = LocalSnackbarHostState.current
    var showLocal by rememberSaveable { mutableStateOf(false) }

    val transparentAppBar by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0
        }
    }

    LaunchedEffect(libraryArtist) {
        // ---  Default to online view if linked ---
        // Show local mode by default ONLY if the artist is local AND unlinked.
        // If the artist is linked (even if local), default to the online view (showLocal = false).
        val artist = libraryArtist?.artist
        showLocal = artist?.isLocal == true && artist.channelId.isNullOrEmpty()
    }

    val artistHead = @Composable {
        if (artistPage != null || libraryArtist != null) {
            val thumbnail = artistPage?.artist?.thumbnail ?: libraryArtist?.artist?.thumbnailUrl
            val artistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name

            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (thumbnail != null) Modifier.aspectRatio(4f / 3) else Modifier
                        )
                ) {
                    if (thumbnail != null) {
                        AsyncImage(
                            model = thumbnail.resize(1200, 900),
                            contentDescription = null,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fadingEdge(
                                    top = WindowInsets.systemBars
                                        .asPaddingValues()
                                        .calculateTopPadding() + AppBarHeight,
                                    bottom = 64.dp
                                )
                        )
                    }
                    AutoResizeText(
                        text = artistName
                            ?: "Unknown",
                        style = MaterialTheme.typography.displayLarge,
                        fontSizeRange = FontSizeRange(32.sp, 58.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 48.dp)
                            .then(
                                if (thumbnail == null) {
                                    Modifier.padding(
                                        top = WindowInsets.systemBars
                                            .asPaddingValues()
                                            .calculateTopPadding() + AppBarHeight
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    // --- ROW 1: Shuffle and Radio buttons ---
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Shuffle Button (Logic is correct)
                        Button(
                            onClick = {
                                val watchEndpoint = artistPage?.artist?.shuffleEndpoint ?: artistPage?.artist?.playEndpoint
                                playerConnection.playQueue(
                                    if (!showLocal && watchEndpoint != null) YouTubeQueue(watchEndpoint)
                                    else ListQueue(
                                        title = artistName,
                                        items = librarySongs.map { it.toMediaMetadata() },
                                        startShuffled = true
                                    ),
                                    isRadio = true,
                                    title = artistName
                                )
                            },
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.shuffle))
                        }

                        // Radio Button (Logic is correct)
                        val radioEndpoint = artistPage?.artist?.radioEndpoint
                        if (radioEndpoint != null) {
                            OutlinedButton(
                                onClick = {
                                    playerConnection.playQueue(
                                        YouTubeQueue(radioEndpoint),
                                        isRadio = true,
                                        title = "Radio: ${artistPage?.artist?.title}"
                                    )
                                },
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Radio,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.radio))
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    // --- ROW 2: Link/Unlink Button (DEFINITIVE FIX) ---
                    val currentArtistEntity = libraryArtist?.artist
                    if (currentArtistEntity != null) {
                        // Case 1: Show LINK button.
                        // This is for a local artist that is NOT linked to YouTube.
                        if (currentArtistEntity.isLocal && !currentArtistEntity.isYouTubeArtist) {
                            OutlinedButton(
                                onClick = { showManualLinkDialog = true },
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.link_artist_button_label))
                            }
                        }
                        // Case 2: Show UNLINK button.
                        // This is for ANY artist in the library that IS linked to YouTube.
                        else if (currentArtistEntity.isYouTubeArtist) {
                            OutlinedButton(
                                onClick = { showUnlinkConfirmDialog = true },
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LinkOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize)
                                )
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.unlink_artist_button_label))
                            }
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .add(
                    WindowInsets(
                        top = -WindowInsets.systemBars.asPaddingValues()
                            .calculateTopPadding() - AppBarHeight
                    )
                )
                .asPaddingValues()
        ) {
            if (isLoading && artistPage == null && !showLocal) {
                item(key = "shimmer") {
                    ArtistPagePlaceholder()
                }
            } else {
                item(key = "header") {
                    artistHead()
                }

                if (showLocal) {
                    if (librarySongs.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.songs),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/songs")
                                }
                            )
                        }

                        val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
                        itemsIndexed(
                            items = librarySongs,
                            key = { _, item -> item.hashCode() }
                        ) { index, song ->
                            SongListItem(
                                song = song,
                                navController = navController,
                                snackbarHostState = snackbarHostState,

                                isActive = song.song.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                inSelectMode = false,
                                isSelected = false,
                                onSelectedChange = { },
                                swipeEnabled = swipeEnabled,

                                thumbnailSize = thumbnailSize,
                                onPlay = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = "Library: ${libraryArtist?.artist?.name}",
                                            items = librarySongs.map { it.toMediaMetadata() },
                                            startIndex = index
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            )

                        }
                    }

                    if (libraryAlbums.isNotEmpty()) {
                        item {
                            NavigationTitle(
                                title = stringResource(R.string.albums),
                                onClick = {
                                    navController.navigate("artist/${viewModel.artistId}/albums")
                                }
                            )
                        }

                        item {
                            LazyRow {
                                items(
                                    items = libraryAlbums,
                                    key = { it.id }
                                ) { album ->
                                    AlbumGridItem(
                                        album = album,
                                        isActive = album.id == mediaMetadata?.album?.id,
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = {
                                                    navController.navigate("album/${album.id}")
                                                },
                                                onLongClick = {
                                                    menuState.show {
                                                        AlbumMenu(
                                                            originalAlbum = album,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss
                                                        )
                                                    }
                                                }
                                            )
                                            .animateItem()
                                    )
                                }
                            }
                        }

                    }
                } else artistPage?.sections?.fastForEach { section ->
                    val isSongsSection = (section.items.firstOrNull() as? SongItem)?.album != null

                    item {
                        NavigationTitle(
                            title = if (isSongsSection) stringResource(R.string.songs) else section.title,
                            onClick = section.moreEndpoint?.let {
                                {
                                    navController.navigate("artist/${viewModel.artistId}/items?browseId=${it.browseId}?params=${it.params}")
                                }
                            }
                        )
                    }

                    if (isSongsSection) {
                        items(
                            items = section.items,
                            key = { it.id }
                        ) { song ->
                            SwipeToQueueBox(
                                item = (song as SongItem).toMediaItem(),
                                swipeEnabled = swipeEnabled,
                                snackbarHostState = snackbarHostState
                            ) {
                                YouTubeListItem(
                                    item = song,
                                    isActive = mediaMetadata?.id == song.id,
                                    isPlaying = isPlaying,
                                    trailingContent = {
                                        IconButton(
                                            onClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss
                                                    )
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.MoreVert,
                                                contentDescription = null
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = {
                                                if (song.id == mediaMetadata?.id) {
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    playerConnection.playQueue(
                                                        ListQueue(
                                                            title = "Artist songs (preview): ${artistPage.artist.title}",
                                                            items = section.items.map { (it as SongItem).toMediaMetadata() },
                                                            startIndex = section.items.indexOf(
                                                                song
                                                            )
                                                        )
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                menuState.show {
                                                    YouTubeSongMenu(
                                                        song = song,
                                                        navController = navController,
                                                        onDismiss = menuState::dismiss
                                                    )
                                                }
                                            }
                                        )
                                        .animateItem()
                                )
                            }

                        }
                    } else {
                        item {
                            LazyRow {
                                items(
                                    items = section.items,
                                    key = { it.id }
                                ) { item ->
                                    YouTubeGridItem(
                                        item = item,
                                        isActive = when (item) {
                                            is SongItem -> mediaMetadata?.id == item.id
                                            is AlbumItem -> mediaMetadata?.album?.id == item.id
                                            else -> false
                                        },
                                        isPlaying = isPlaying,
                                        coroutineScope = coroutineScope,
                                        modifier = Modifier
                                            .combinedClickable(
                                                onClick = {
                                                    when (item) {
                                                        is SongItem -> playerConnection.playQueue(
                                                            YouTubeQueue.radio(
                                                                item.toMediaMetadata()
                                                            ),
                                                            isRadio = true,
                                                            title = artistPage.artist.title
                                                        )

                                                        is AlbumItem -> navController.navigate(
                                                            "album/${item.id}"
                                                        )

                                                        is ArtistItem -> navController.navigate(
                                                            "artist/${item.id}"
                                                        )

                                                        is PlaylistItem -> navController.navigate(
                                                            "online_playlist/${item.id}"
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    menuState.show {
                                                        when (item) {
                                                            is SongItem -> YouTubeSongMenu(
                                                                song = item,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is AlbumItem -> YouTubeAlbumMenu(
                                                                albumItem = item,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is ArtistItem -> YouTubeArtistMenu(
                                                                artist = item,
                                                                onDismiss = menuState::dismiss
                                                            )

                                                            is PlaylistItem -> YouTubePlaylistMenu(
                                                                navController = navController,
                                                                playlist = item,
                                                                coroutineScope = coroutineScope,
                                                                onDismiss = menuState::dismiss
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            .animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        LazyColumnScrollbar(
            state = lazyListState,
        )

        HideOnScrollFAB(
            //  Show toggle when both views are available ---
            visible = artistPage != null && librarySongs.isNotEmpty(),
            lazyListState = lazyListState,
            icon = if (showLocal) Icons.Rounded.LibraryMusic else Icons.Rounded.Language,
            onClick = {
                showLocal = showLocal.not()
                if (!showLocal && artistPage == null) viewModel.fetchArtistsFromYTM()
            }
        )

        if (showManualLinkDialog) {
            ManualLinkDialog(
                artistName = libraryArtist?.artist?.name ?: "",
                viewModel = viewModel,
                onDismiss = { showManualLinkDialog = false },
                onConfirm = { selectedArtistId ->
                    showManualLinkDialog = false
                    viewModel.linkArtist(selectedArtistId) {
                        coroutineScope.launch {
                            navController.popBackStack()
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.link_successful_snackbar),
                                duration = SnackbarDuration.Long
                            )

                        }
                    }
                }
            )
        }
        // ---  show the unlink confirmation dialog ---
        if (showUnlinkConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showUnlinkConfirmDialog = false },
                title = { Text(stringResource(R.string.unlink_artist_dialog_title)) },
                text = { Text(stringResource(R.string.artist_unlink_confirm_message)) },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnlinkConfirmDialog = false
                            // Unlink and navigate back ---\
                            viewModel.unlinkArtist {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.unlink_artist_dialog_confirm_button))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnlinkConfirmDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }


        TopAppBar(
            title = { if (!transparentAppBar) Text(artistPage?.artist?.title.orEmpty()) },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        database.transaction {
                            val artist = libraryArtist?.artist
                            if (artist != null) {
                                update(artist.toggleLike())
                            } else {
                                artistPage?.artist?.let {
                                    insert(
                                        ArtistEntity(
                                            id = it.id,
                                            name = it.title,
                                            channelId = it.channelId,
                                            thumbnailUrl = it.thumbnail,
                                        ).toggleLike()
                                    )
                                }
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (libraryArtist?.artist?.bookmarkedAt != null) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        tint = if (libraryArtist?.artist?.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        contentDescription = null
                    )
                }

                IconButton(
                    onClick = {
                        viewModel.artistPage?.artist?.shareLink?.let { link ->
                            val intent = Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, link)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = null
                    )
                }
            },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
            colors = if (transparentAppBar) {
                TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            } else {
                TopAppBarDefaults.topAppBarColors()
            }
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .align(Alignment.BottomCenter)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualLinkDialog(
    artistName: String,
    viewModel: ArtistViewModel,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by rememberSaveable(artistName) { mutableStateOf(artistName) }
    val searchResults = remember { mutableStateListOf<ArtistItem>() }
    var selectedArtist by remember { mutableStateOf<ArtistItem?>(null) }
    val focusRequester = remember { FocusRequester() }

    fun doSearch() {
        searchResults.clear()
        selectedArtist = null
        viewModel.searchArtists(searchQuery) { results ->
            searchResults.addAll(results)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_artist_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.link_artist_dialog_text))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.link_artist_search_query_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                    trailingIcon = {
                        IconButton(onClick = { doSearch() }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                    }
                )

                val resultsLazyListState = rememberLazyListState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                ) {
                    LazyColumn(
                        state = resultsLazyListState,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (searchResults.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.search_results_title),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            // Using the explicit Items constructor to help the compiler identify ArtistItem
                            items(
                                items = searchResults,
                                key = { it.id }
                            ) { artistItem: ArtistItem ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedArtist = artistItem }
                                        .background(
                                            if (selectedArtist == artistItem) MaterialTheme.colorScheme.primaryContainer
                                            else Color.Transparent
                                        )
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Artist Thumbnail
                                    AsyncImage(
                                        model = artistItem.thumbnail?.resize(120, 120),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(24.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    )

                                    Spacer(Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = artistItem.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        // Subscriber count (if provided by YouTube search)
                                        val subscribersText = artistItem.subscribers
                                        if (!subscribersText.isNullOrBlank()) {
                                            Text(
                                                text = subscribersText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LazyColumnScrollbar(state = resultsLazyListState)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedArtist?.id?.let { onConfirm(it) } },
                enabled = selectedArtist != null
            ) {
                Text(stringResource(R.string.link_artist_button_label))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        doSearch()
    }
}