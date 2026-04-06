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

fun getScreensaverDimmingOptions(): List<Pair<Int, String>> = listOf(
	0 to "Off",
	5 to "5%",
	10 to "10%",
	15 to "15%",
	20 to "20%",
	25 to "25%",
	30 to "30%",
	35 to "35%",
	40 to "40%",
	45 to "45%",
	50 to "50%",
	55 to "55%",
	60 to "60%",
	65 to "65%",
	70 to "70%",
	75 to "75%",
	80 to "80%",
	85 to "85%",
	90 to "90%",
	95 to "95%",
	100 to "100%",
)

@Composable
fun SettingsScreensaverDimmingScreen() {
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_screensaver_dimming)) },
				captionContent = { Text(stringResource(R.string.pref_screensaver_dimming_level_description)) },
			)
		}

		getScreensaverDimmingOptions().forEach { (value, label) ->
			item(key = value) {
				var screensaverDimmingLevel by rememberPreference(userPreferences, UserPreferences.screensaverDimmingLevel)

				ListButton(
					headingContent = { Text(label) },
					trailingContent = { RadioButton(checked = screensaverDimmingLevel == value) },
					onClick = { screensaverDimmingLevel = value }
				)
			}
		}
	}
}
