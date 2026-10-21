package org.jellyfin.androidtv.ui.settings.screen.about

import android.content.ClipData
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.util.copyAction
import org.jellyfin.androidtv.ui.update.UpdatePrompt
import org.jellyfin.androidtv.ui.update.findFragmentActivity
import org.koin.compose.koinInject

@Composable
fun SettingsAboutScreen(launchedFromLogin: Boolean = false) {
	val router = LocalRouter.current

	SettingsColumn {
		if (launchedFromLogin) item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_login).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		} else item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		}

		item {
			val heading = "Tentacle app version"
			val caption = "tentacle-androidtv ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tentacle), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
			)
		}

		if (BuildConfig.ENABLE_OTA_UPDATES) item {
			val context = LocalContext.current
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

		if (BuildConfig.ENABLE_OTA_UPDATES) item {
			val userPreferences = koinInject<UserPreferences>()
			var notifyEnabled by rememberPreference(userPreferences, UserPreferences.updateNotificationsEnabled)
			ListButton(
				headingContent = { Text(stringResource(R.string.update_notify_title)) },
				trailingContent = { Checkbox(checked = notifyEnabled) },
				captionContent = {
					Text(stringResource(if (notifyEnabled) R.string.update_notify_on else R.string.update_notify_off))
				},
				onClick = { notifyEnabled = !notifyEnabled },
			)
		}

		item {
			val heading = stringResource(R.string.pref_device_model)
			val caption = "${Build.MANUFACTURER} ${Build.MODEL}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_guide), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.licenses_link)) },
				onClick = { router.push(Routes.LICENSES) },
			)
		}
	}
}
