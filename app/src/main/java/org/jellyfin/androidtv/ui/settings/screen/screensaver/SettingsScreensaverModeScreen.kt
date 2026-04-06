package org.jellyfin.androidtv.ui.settings.screen.screensaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsScreensaverModeScreen() {
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver_mode)) },
			)
		}

		item {
			var screensaverMode by rememberPreference(userPreferences, UserPreferences.screensaverMode)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_mode_library)) },
				trailingContent = { RadioButton(checked = screensaverMode == "library") },
				onClick = { screensaverMode = "library" }
			)
		}

		item {
			var screensaverMode by rememberPreference(userPreferences, UserPreferences.screensaverMode)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_screensaver_mode_logo)) },
				trailingContent = { RadioButton(checked = screensaverMode == "logo") },
				onClick = { screensaverMode = "logo" }
			)
		}
	}
}
