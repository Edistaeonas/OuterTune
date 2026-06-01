package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AudioNormalizationKey
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.constants.AudioQualityKey
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.GlobalRadioArtistRepeatThresholdKey
import com.dd3boh.outertune.constants.GlobalRadioArtistsCountKey
import com.dd3boh.outertune.constants.GlobalRadioLocalAndLikedSongsCountKey
import com.dd3boh.outertune.constants.GlobalRadioSongsCountKey
import com.dd3boh.outertune.constants.GlobalRadioStrictFavoritesKey
import com.dd3boh.outertune.constants.GlobalRadioUseLocalKey
import com.dd3boh.outertune.constants.GlobalRadioUseOnlineKey
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.SkipOnErrorKey
import com.dd3boh.outertune.constants.SkipSilenceKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.constants.minPlaybackDurKey
import com.dd3boh.outertune.ui.component.EnumListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.CounterDialog
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun PlayerGeneralFrag() {
    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(AutoLoadMoreKey, defaultValue = true)

    val context = LocalContext.current
    val (seekIncrement, onSeekIncrementChange) = rememberEnumPreference(
        key = SeekIncrementKey,
        defaultValue = SeekIncrement.OFF
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.auto_load_more)) },
        description = stringResource(R.string.auto_load_more_desc),
        icon = { Icon(Icons.Rounded.Autorenew, null) },
        checked = autoLoadMore,
        onCheckedChange = onAutoLoadMoreChange
    )
    EnumListPreference(
        title = { Text(stringResource(R.string.seek_increment))},
        icon = { Icon(Icons.Rounded.FastForward, null) },
        selectedValue = seekIncrement,
        onValueSelected = onSeekIncrementChange,
        valueText = {
            seekIncrement -> SeekIncrement.getString(context, seekIncrement)
        }
    )
}

@Composable
fun PlayerServiceFrag() {

}

@Composable
fun AudioQualityFrag() {
    val (audioQuality, onAudioQualityChange) = rememberEnumPreference(
        key = AudioQualityKey,
        defaultValue = AudioQuality.AUTO
    )

    EnumListPreference(
        title = { Text(stringResource(R.string.audio_quality)) },
        icon = { Icon(Icons.Rounded.GraphicEq, null) },
        selectedValue = audioQuality,
        onValueSelected = onAudioQualityChange,
        valueText = {
            when (it) {
                AudioQuality.AUTO -> stringResource(R.string.audio_quality_auto)
                AudioQuality.HIGH -> stringResource(R.string.audio_quality_high)
                AudioQuality.LOW -> stringResource(R.string.audio_quality_low)
            }
        }
    )

}

@Composable
fun AudioEffectsFrag() {
    val (skipSilence, onSkipSilenceChange) = rememberPreference(key = SkipSilenceKey, defaultValue = false)

    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        key = AudioNormalizationKey,
        defaultValue = true
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.audio_normalization)) },
        icon = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null) },
        checked = audioNormalization,
        onCheckedChange = onAudioNormalizationChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.skip_silence)) },
        icon = { Icon(painterResource(R.drawable.skip_next), null) },
        checked = skipSilence,
        onCheckedChange = onSkipSilenceChange
    )

}

@Composable
fun PlaybackBehaviourFrag() {
    val keepAlive by rememberPreference(key = KeepAliveKey, defaultValue = false)
    val (minPlaybackDur, onMinPlaybackDurChange) = rememberPreference(minPlaybackDurKey, defaultValue = 30)
    val (skipOnErrorKey, onSkipOnErrorChange) = rememberPreference(key = SkipOnErrorKey, defaultValue = true)
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        key = StopMusicOnTaskClearKey,
        defaultValue = false
    )

    var showMinPlaybackDur by remember {
        mutableStateOf(false)
    }

    PreferenceEntry(
        title = { Text(stringResource(R.string.min_playback_duration)) },
        icon = { Icon(Icons.Rounded.Sync, null) },
        onClick = { showMinPlaybackDur = true }
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
        description = stringResource(R.string.auto_skip_next_on_error_desc),
        icon = { Icon(Icons.Rounded.SkipNext, null) },
        checked = skipOnErrorKey,
        onCheckedChange = onSkipOnErrorChange
    )
    SwitchPreference(
        title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
        icon = { Icon(Icons.Rounded.ClearAll, null) },
        isEnabled = !keepAlive,
        checked = stopMusicOnTaskClear,
        onCheckedChange = onStopMusicOnTaskClearChange,
    )

    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showMinPlaybackDur) {
        CounterDialog(
            title = stringResource(R.string.min_playback_duration),
            description = stringResource(R.string.min_playback_duration_description),
            initialValue = minPlaybackDur,
            upperBound = 100,
            lowerBound = 0,
            unitDisplay = "%",
            onDismiss = { showMinPlaybackDur = false },
            onConfirm = {
                showMinPlaybackDur = false
                onMinPlaybackDurChange(it)
            },
            onCancel = {
                showMinPlaybackDur = false
            }
        )
    }
}

@Composable
fun GlobalRadioFrag() {
    var showArtistCountDialog by remember { mutableStateOf(false) }
    var showSongCountDialog by remember { mutableStateOf(false) }
    var showLocalSongCountDialog by remember { mutableStateOf(false) }
    var showArtistRepeatThresholdDialog by remember { mutableStateOf(false) }

    // Existing settings
    val (artistCount, onArtistCountChange) = rememberPreference(GlobalRadioArtistsCountKey, defaultValue = 4)
    val (songCount, onSongCountChange) = rememberPreference(GlobalRadioSongsCountKey, defaultValue = 3)
    val (localSongCount, onLocalSongCountChange) = rememberPreference(GlobalRadioLocalAndLikedSongsCountKey, defaultValue = 10)
    val (useOnline, onUseOnlineChange) = rememberPreference(GlobalRadioUseOnlineKey, defaultValue = true)
    val (useLocal, onUseLocalChange) = rememberPreference(GlobalRadioUseLocalKey, defaultValue = true)
    val (strictFavorites, onStrictFavoritesChange) = rememberPreference(GlobalRadioStrictFavoritesKey, defaultValue = false)
    val (artistRepeatThreshold, onArtistRepeatThresholdChange) = rememberPreference(GlobalRadioArtistRepeatThresholdKey, defaultValue = 50)



    SwitchPreference(
        title = { Text(stringResource(R.string.global_radio_use_online_title)) },
        description = stringResource(R.string.global_radio_use_online_description),
        checked = useOnline,
        onCheckedChange = onUseOnlineChange
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.global_radio_use_local_title)) },
        description = stringResource(R.string.global_radio_use_local_description),
        checked = useLocal,
        onCheckedChange = onUseLocalChange
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.global_radio_strict_favorites_title)) },
        description = stringResource(R.string.global_radio_strict_favorites_description),
        checked = strictFavorites,
        onCheckedChange = onStrictFavoritesChange
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.global_radio_local_liked_songs_count)) },
        description = stringResource(R.string.global_radio_local_liked_songs_count_desc),
        icon = { Icon(Icons.Rounded.LibraryMusic, null) },
        onClick = { showLocalSongCountDialog = true }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.global_radio_artists_count)) },
        description = stringResource(R.string.global_radio_artists_count_desc),
        icon = { Icon(Icons.Rounded.Person, null) },
        onClick = { showArtistCountDialog = true }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.global_radio_songs_count)) },
        description = stringResource(R.string.global_radio_songs_count_desc),
        icon = { Icon(Icons.Rounded.QueueMusic, null) },
        onClick = { showSongCountDialog = true }
    )

    // --- NEW: Artist Separation Gap Entry ---
    PreferenceEntry(
        title = { Text(stringResource(R.string.global_radio_artist_separation_gap)) },
        description = stringResource(R.string.global_radio_artist_separation_gap_desc),
        icon = { Icon(Icons.Rounded.Repeat, null) },
        onClick = { showArtistRepeatThresholdDialog = true }
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */

    if (showLocalSongCountDialog) {
        CounterDialog(
            title = stringResource(R.string.global_radio_local_liked_songs_count),
            initialValue = localSongCount,
            upperBound = 100,
            lowerBound = 0,
            onDismiss = { showLocalSongCountDialog = false },
            onConfirm  = {
                showLocalSongCountDialog = false
                onLocalSongCountChange(it)
            },
            onCancel = { showLocalSongCountDialog = false }
        )
    }

    if (showArtistCountDialog) {
        CounterDialog(
            title = stringResource(R.string.global_radio_artists_count),
            initialValue = artistCount,
            upperBound = 50,
            lowerBound = 1,
            onDismiss = { showArtistCountDialog = false },
            onConfirm  = {
                showArtistCountDialog = false
                onArtistCountChange(it)
            },
            onCancel = { showArtistCountDialog = false }
        )
    }

    if (showSongCountDialog) {
        CounterDialog(
            title = stringResource(R.string.global_radio_songs_count),
            initialValue = songCount,
            upperBound = 50,
            lowerBound = 1,
            onDismiss = { showSongCountDialog = false },
            onConfirm = {
                showSongCountDialog = false
                onSongCountChange(it)
            },
            onCancel = { showSongCountDialog = false }
        )
    }

    // --- NEW: Artist Separation Gap Dialog ---
    if (showArtistRepeatThresholdDialog) {
        CounterDialog(
            title = stringResource(R.string.global_radio_artist_separation_gap),
            description = stringResource(R.string.global_radio_artist_separation_gap_desc),
            initialValue = artistRepeatThreshold,
            upperBound = 100,
            lowerBound = 0,
            onDismiss = { showArtistRepeatThresholdDialog = false },
            onConfirm = {
                showArtistRepeatThresholdDialog = false
                onArtistRepeatThresholdChange(it)
            },
            onCancel = { showArtistRepeatThresholdDialog = false }
        )
    }
}