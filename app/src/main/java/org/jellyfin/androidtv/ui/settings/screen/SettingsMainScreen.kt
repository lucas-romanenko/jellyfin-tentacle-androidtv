package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.update.UpdatePrompt
import org.jellyfin.androidtv.ui.update.findFragmentActivity
import org.koin.compose.koinInject

/** Presses on the version that open the hidden developer options. */
private const val DEVELOPER_PRESSES = 7

/**
 * The whole of the TV's Settings (2026-10-22): who's watching, the screensaver switch, updates
 * and About. Everything a person customises (home rows, hero) lives in Tentacle; the look is
 * fixed ([org.jellyfin.androidtv.preference.FixedLook]); subtitle and audio languages follow
 * the person's Jellyfin preferences. The developer options open after
 * [DEVELOPER_PRESSES] presses on the version.
 */
@Composable
fun SettingsMainScreen() {
	val router = LocalRouter.current
	val context = LocalContext.current
	val userPreferences = koinInject<UserPreferences>()
	val userRepository = koinInject<UserRepository>()
	val serverRepository = koinInject<ServerRepository>()
	val serverUserRepository = koinInject<ServerUserRepository>()
	val sessionRepository = koinInject<SessionRepository>()
	val authenticationRepository = koinInject<AuthenticationRepository>()
	val mediaManager = koinInject<MediaManager>()

	val currentUser by userRepository.currentUser.collectAsState()
	val currentServer by serverRepository.currentServer.collectAsState()
	val userName = currentUser?.name.orEmpty()

	// Back to the "Who's watching?" screen; signing out first forgets this person's sign-in.
	fun leave(activity: FragmentActivity, signOut: Boolean) {
		if (signOut) {
			val session = sessionRepository.currentSession.value
			val server = currentServer
			val user = if (session != null && server != null) {
				serverUserRepository.getStoredServerUsers(server).find { it.id == session.userId }
			} else null
			if (user != null) authenticationRepository.logout(user)
		}
		mediaManager.clearAudioQueue()
		sessionRepository.destroyCurrentSession()
		activity.startActivity(ActivityDestinations.startup(activity))
		activity.finishAfterTransition()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.app_name).uppercase()) },
				headingContent = { Text(stringResource(R.string.settings)) },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_users), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.settings_switch_user)) },
				captionContent = { Text(stringResource(R.string.settings_switch_user_caption)) },
				onClick = { context.findFragmentActivity()?.let { leave(it, signOut = false) } },
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.settings_log_out)) },
				captionContent = { Text(stringResource(R.string.settings_log_out_caption, userName)) },
				onClick = { context.findFragmentActivity()?.let { leave(it, signOut = true) } },
			)
		}

		item {
			var screensaverEnabled by rememberPreference(userPreferences, UserPreferences.screensaverInAppEnabled)
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photos), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_screensaver)) },
				trailingContent = { Checkbox(checked = screensaverEnabled) },
				captionContent = { Text(stringResource(R.string.settings_screensaver_caption)) },
				onClick = { screensaverEnabled = !screensaverEnabled },
			)
		}

		if (BuildConfig.ENABLE_OTA_UPDATES) item {
			var checking by remember { mutableStateOf(false) }
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.update_check_title)) },
				captionContent = {
					Text(stringResource(if (checking) R.string.update_checking else R.string.update_check_caption))
				},
				onClick = {
					val activity = context.findFragmentActivity()
					if (!checking && activity != null) {
						checking = true
						// Same dialog as on launch: release notes, then Update now or Later
						UpdatePrompt.checkNow(activity, onDone = { checking = false })
					}
				},
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.pref_about_title)) }) }

		item {
			var presses by remember { mutableIntStateOf(0) }
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tentacle), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.settings_app_version)) },
				captionContent = { Text("${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}") },
				onClick = {
					presses++
					if (presses >= DEVELOPER_PRESSES) {
						presses = 0
						router.push(Routes.DEVELOPER)
					}
				},
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_house), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.lbl_server)) },
				captionContent = { Text(currentServer?.address.orEmpty()) },
				onClick = {},
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_user), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.settings_signed_in_as)) },
				captionContent = { Text(userName) },
				onClick = {},
			)
		}
	}
}
