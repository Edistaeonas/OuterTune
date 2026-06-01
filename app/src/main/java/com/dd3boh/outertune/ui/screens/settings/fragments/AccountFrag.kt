/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.screens.settings.fragments

import android.util.Log
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavController
import com.dd3boh.outertune.App.Companion.forgetAccount
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.AccountChannelHandleKey
import com.dd3boh.outertune.constants.AccountEmailKey
import com.dd3boh.outertune.constants.AccountNameKey
import com.dd3boh.outertune.constants.DataSyncIdKey
import com.dd3boh.outertune.constants.InnerTubeCookieKey
import com.dd3boh.outertune.constants.OobeStatusKey
import com.dd3boh.outertune.constants.UseLoginForBrowse
import com.dd3boh.outertune.constants.VisitorDataKey
import com.dd3boh.outertune.constants.VrVerKey
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.dialog.InfoLabel
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.utils.rememberPreference
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VER_KEY
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS_VER_KEY
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_VER_KEY
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_FALLBACK_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_ALT1_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_ALT2_VERSION
import com.zionhuang.innertube.models.YouTubeClient.Companion.VR_CLIENT_LATEST_VERSION
import com.zionhuang.innertube.utils.parseCookieString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Web
import com.dd3boh.outertune.playback.MediaControllerViewModel
import com.dd3boh.outertune.playback.MusicService



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.AccountFrag(navController: NavController) {
    val context = LocalContext.current
    val binder: MediaControllerViewModel = hiltViewModel()

    val (accountName, onAccountNameChange) = rememberPreference(AccountNameKey, "")
    val (accountEmail, onAccountEmailChange) = rememberPreference(AccountEmailKey, "")
    val (accountChannelHandle, onAccountChannelHandleChange) = rememberPreference(AccountChannelHandleKey, "")
    val (innerTubeCookie, onInnerTubeCookieChange) = rememberPreference(InnerTubeCookieKey, "")
    val (visitorData, onVisitorDataChange) = rememberPreference(VisitorDataKey, "")
    val (dataSyncId, onDataSyncIdChange) = rememberPreference(DataSyncIdKey, "")
    val (oobeStatus) = rememberPreference(OobeStatusKey, 0)
    val isLoggedIn = remember(innerTubeCookie) {
        "SAPISID" in parseCookieString(innerTubeCookie)
    }

    // 1. Define the keys for storage
    //val VrVerKey = stringPreferencesKey("vr_version_override") -> now in constants
    val WebVerKey = stringPreferencesKey("web_version_override")
    val AndroidVerKey = stringPreferencesKey("android_version_override")
    val IosVerKey = stringPreferencesKey("ios_version_override")

    // 2. Load the states (defaulting to the current known working versions)
    val (vrVer, onVrVerChange) = rememberPreference(VrVerKey, VR_CLIENT_FALLBACK_VERSION)
    val (webVer, onWebVerChange) = rememberPreference(WebVerKey, WEB_VER_KEY)
    val (androidVer, onAndroidVerChange) = rememberPreference(AndroidVerKey, ANDROID_VER_KEY)
    val (iosVer, onIosVerChange) = rememberPreference(IosVerKey, IOS_VER_KEY)

    // 3. Update the static variables in the library whenever the user changes them in UI
    LaunchedEffect(vrVer, webVer, androidVer, iosVer) {
        YouTubeClient.currentVrVersion = vrVer
        YouTubeClient.currentWebVersion = webVer
        YouTubeClient.currentAndroidVersion = androidVer
        YouTubeClient.currentIosVersion = iosVer
    }



    // 4. Dialog Visibility States
    var showVrDialog by remember { mutableStateOf(false) }
    var showWebDialog by remember { mutableStateOf(false) }
    var showAndroidDialog by remember { mutableStateOf(false) }
    var showIosDialog by remember { mutableStateOf(false) }

    var showResetConfirmation by remember { mutableStateOf(false) }
    var showRestartRequiredByVersionChange by remember { mutableStateOf(false) }

    // temp vars
    var showToken: Boolean by remember {
        mutableStateOf(false)
    }
    var showTokenEditor by remember {
        mutableStateOf(false)
    }


    // --- LOGIN RESTART TRIGGER ---
    // We track if this is the first time the screen loads to avoid
    // triggering a restart prompt immediately upon opening the settings.
    var isFirstLoad by remember { mutableStateOf(true) }

    var isManualVersionChange by remember { mutableStateOf(false) }

    LaunchedEffect(isLoggedIn) {
        Log.i("AccountFrag", "LOGIN_STATE_LOG: isLoggedIn=$isLoggedIn, isFirstLoad=$isFirstLoad, currentVrVer=$vrVer")
        Log.i("AccountFrag", "LOGIN_STATE_LOG: Current YouTube.visitorData=${YouTube.visitorData}")

        if (isLoggedIn) {
            // If the preference is still on Fallback (.14), it means a login just happened
            // (either via Wizard or Settings) and we haven't performed the .15 upgrade yet.
            if (vrVer == VR_CLIENT_FALLBACK_VERSION) {
                Log.w("AccountFrag", "LOGIN_STATE_LOG: Fresh login detected. Upgrading to Latest.")

                // Set to .15 immediately
                onVrVerChange(VR_CLIENT_LATEST_VERSION)

                // If the app has already done an anonymous handshake, we MUST restart to clean sockets
                if (YouTube.visitorData != null) {
                    showRestartRequiredByVersionChange = true
                } else {
                    // Extremely rare case: login happened so fast no handshake occurred.
                    // Just update the memory version.
                    YouTubeClient.currentVrVersion = VR_CLIENT_LATEST_VERSION
                }
            }
        }
        isFirstLoad = false
    }

    PreferenceEntry(
        title = { Text(if (isLoggedIn) accountName else stringResource(R.string.login)) },
        description = if (isLoggedIn) {
            accountEmail.takeIf { it.isNotEmpty() }
                ?: accountChannelHandle.takeIf { it.isNotEmpty() }
        } else null,
        icon = { Icon(Icons.Rounded.Person, null) },
        onClick = { navController.navigate("login") }
    )
    if (isLoggedIn) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.action_logout)) },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            onClick = {
                forgetAccount(context)
            }
        )
        Spacer(Modifier.height(8.dp))
        InfoLabel(stringResource(R.string.action_logout_tooltip))
        Spacer(Modifier.height(24.dp))
    }

    if (oobeStatus >= 3) {
        PreferenceEntry(
            title = {
                // Main Title
                Text(stringResource(R.string.force_youtube_reset))
                // Styled Description to match expert_settings_desc
                Text(
                    text = stringResource(
                        R.string.force_youtube_reset_desc,
                        VR_CLIENT_LATEST_VERSION,
                        VR_CLIENT_FALLBACK_VERSION,
                        VR_CLIENT_ALT1_VERSION,
                        VR_CLIENT_ALT2_VERSION
                        //1.72.15, 1.72.14, 1.70.10 and 1.61.48
                    ),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Light
                )
            },
            icon = { Icon(Icons.Rounded.Refresh, null) },
            onClick = {
                showResetConfirmation = true
            }
        )
    }


    // --- EXPERT SETTINGS SECTION ---
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.expert_settings_title),
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
    Text(
        text = stringResource(R.string.expert_settings_desc),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Light,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    Spacer(Modifier.height(8.dp))

    // 5. Corrected UI Entries using PreferenceEntry
    PreferenceEntry(
        title = { Text(stringResource(R.string.vr_version_title)) },
        description = stringResource(R.string.client_version_desc, vrVer, stringResource(R.string.client_vr_label)),
        icon = { Icon(Icons.Rounded.SettingsInputAntenna, null) },
        onClick = { showVrDialog = true }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.web_version_title)) },
        description = stringResource(R.string.client_version_desc, webVer, stringResource(R.string.client_web_label)),
        icon = { Icon(Icons.Rounded.Web, null) },
        onClick = { showWebDialog = true }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.android_version_title)) },
        description = stringResource(R.string.client_version_desc, androidVer, stringResource(R.string.client_fallback_label)),
        icon = { Icon(Icons.Rounded.Android, null) },
        onClick = { showAndroidDialog = true }
    )

    PreferenceEntry(
        title = { Text(stringResource(R.string.ios_version_title)) },
        description = stringResource(R.string.client_version_desc, iosVer, stringResource(R.string.client_fallback_label)),
        icon = { Icon(Icons.Rounded.PhoneIphone, null) },
        onClick = { showIosDialog = true }
    )



    PreferenceEntry(
        title = {
            if (showToken) {
                Text(stringResource(R.string.token_shown))
                Text(
                    text = if (isLoggedIn) innerTubeCookie else stringResource(R.string.not_logged_in),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Light,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1 // just give a preview so user knows it's at least there
                )
            } else {
                Text(stringResource(R.string.token_hidden))
            }
        },
        onClick = {
            if (showToken == false) {
                showToken = true
            } else {
                showTokenEditor = true
            }
        },
    )


    /**
     * ---------------------------
     * Dialogs
     * ---------------------------
     */


    if (showTokenEditor) {
        val text =
            "***INNERTUBE COOKIE*** =${innerTubeCookie}\n\n***VISITOR DATA*** =${visitorData}\n\n***DATASYNC ID*** =${dataSyncId}\n\n***ACCOUNT NAME*** =${accountName}\n\n***ACCOUNT EMAIL*** =${accountEmail}\n\n***ACCOUNT CHANNEL HANDLE*** =${accountChannelHandle}"
        TextFieldDialog(
            modifier = Modifier,
            initialTextFieldValue = TextFieldValue(text),
            onDone = { data ->
                data.split("\n").forEach {
                    if (it.startsWith("***INNERTUBE COOKIE*** =")) {
                        onInnerTubeCookieChange(it.substringAfter("***INNERTUBE COOKIE*** ="))
                    } else if (it.startsWith("***VISITOR DATA*** =")) {
                        onVisitorDataChange(it.substringAfter("***VISITOR DATA*** ="))
                    } else if (it.startsWith("***DATASYNC ID*** =")) {
                        onDataSyncIdChange(it.substringAfter("***DATASYNC ID*** ="))
                    } else if (it.startsWith("***ACCOUNT NAME*** =")) {
                        onAccountNameChange(it.substringAfter("***ACCOUNT NAME*** ="))
                    } else if (it.startsWith("***ACCOUNT EMAIL*** =")) {
                        onAccountEmailChange(it.substringAfter("***ACCOUNT EMAIL*** ="))
                    } else if (it.startsWith("***ACCOUNT CHANNEL HANDLE*** =")) {
                        onAccountChannelHandleChange(it.substringAfter("***ACCOUNT CHANNEL HANDLE*** ="))
                    }
                }
            },
            onDismiss = { showTokenEditor = false },
            singleLine = false,
            maxLines = 20,
            isInputValid = {
                it.isNotEmpty() &&
                        try {
                            "SAPISID" in parseCookieString(it)
                            true
                        } catch (e: Exception) {
                            false
                        }
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.token_adv_login_description))
            }
        )
    }


    if (showVrDialog) {
        TextFieldDialog(
            initialTextFieldValue = TextFieldValue(vrVer),
            onDone = {
                Log.i("AccountFrag", "MANUAL_EDIT_LOG: User entered VR version: $it")
                onVrVerChange(it) // Save to DataStore
                isManualVersionChange = true
                showRestartRequiredByVersionChange = true
            },
            onDismiss = { showVrDialog = false },
            title = { Text("VR Version") }
        )
    }
    if (showWebDialog) {
        TextFieldDialog(
            initialTextFieldValue = TextFieldValue(webVer),
            onDone = {
                Log.i("AccountFrag", "MANUAL_EDIT_LOG: User entered Web version: $it")
                onWebVerChange(it)
                isManualVersionChange = true
                // Version changed, now need restart
                showRestartRequiredByVersionChange = true },
            onDismiss = { showWebDialog = false },
            title = { Text("Web Remix Version") }
        )
    }
    if (showAndroidDialog) {
        TextFieldDialog(
            initialTextFieldValue = TextFieldValue(androidVer),
            onDone = {
                Log.i("AccountFrag", "MANUAL_EDIT_LOG: User entered Android version: $it")
                onAndroidVerChange(it)
                isManualVersionChange = true
                // Version changed, now need restart
                showRestartRequiredByVersionChange = true },
            onDismiss = { showAndroidDialog = false },
            title = { Text("Android Version") }
        )
    }
    if (showIosDialog) {
        TextFieldDialog(
            initialTextFieldValue = TextFieldValue(iosVer),
            onDone = {
                Log.i("AccountFrag", "MANUAL_EDIT_LOG: User entered iOS version: $it")
                onIosVerChange(it)
                isManualVersionChange = true
                // Version changed, now need restart
                showRestartRequiredByVersionChange = true },
            onDismiss = { showIosDialog = false },
            title = { Text("iOS Version") }
        )
    }

    // 1. Confirmation for clicking the RESET button
    if (showResetConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.restart_confirm_title)) },
            text = { Text(stringResource(R.string.restart_confirm_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showResetConfirmation = false
                    // USE ROTATE = TRUE: User is stuck, wants a new identity. This will log out the user too.
                    MusicService.instance?.forceYoutubeReset(
                        rotate = true,
                        clearCookies = true,
                        autoPlay = false) // User action: DO NOT autoplay on restart)
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 2. Combined dialog for Manual Version Change OR Login transition
    if (showRestartRequiredByVersionChange) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRestartRequiredByVersionChange = false
                isManualVersionChange = false },
            title = { Text(stringResource(R.string.restart_required_title)) },
            text = { Text(stringResource(R.string.restart_required_desc)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val manual = isManualVersionChange
                    val currentTypedValue = vrVer // The value from the TextField

                    showRestartRequiredByVersionChange = false
                    isManualVersionChange = false

                    MusicService.instance?.forceYoutubeReset(
                        rotate = false,
                        // If manual edit, use the string you typed.
                        // If login restart, use the latest version string.
                        targetVersion = if (manual) currentTypedValue else YouTubeClient.VR_CLIENT_LATEST_VERSION,
                        clearCookies = manual,
                        autoPlay = false // User action: DO NOT autoplay on restart
                    )
                }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }


}

@Composable
fun ColumnScope.AccountExtrasFrag() {
    val (useLoginForBrowse, onUseLoginForBrowseChange) = rememberPreference(UseLoginForBrowse, true)

    SwitchPreference(
        title = { Text(stringResource(R.string.use_login_for_browse)) },
        description = stringResource(R.string.use_login_for_browse_desc),
        icon = { Icon(Icons.Rounded.Person, null) },
        checked = useLoginForBrowse,
        onCheckedChange = {
            YouTube.useLoginForBrowse = it
            onUseLoginForBrowseChange(it)
        }
    )
}
