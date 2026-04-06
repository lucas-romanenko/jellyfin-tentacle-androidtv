package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPluginThemeMusicScreen() {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_plugin_settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_theme_music_title)) },
			)
		}

		item {
			var themeMusicEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.themeMusicEnabled)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_theme_music_enable)) },
				captionContent = { Text(stringResource(R.string.pref_theme_music_enable_summary)) },
				trailingContent = { Checkbox(checked = themeMusicEnabled) },
				onClick = { themeMusicEnabled = !themeMusicEnabled }
			)
		}

		item {
			val themeMusicEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.themeMusicEnabled)
			var themeMusicOnHomeRows by rememberPreference(userSettingPreferences, UserSettingPreferences.themeMusicOnHomeRows)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_theme_music_on_home_rows)) },
				captionContent = { Text(stringResource(R.string.pref_theme_music_on_home_rows_summary)) },
				trailingContent = { Checkbox(checked = themeMusicOnHomeRows) },
				enabled = themeMusicEnabled,
				onClick = { themeMusicOnHomeRows = !themeMusicOnHomeRows }
			)
		}

		item {
			val themeMusicEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.themeMusicEnabled)
			val themeMusicVolume by rememberPreference(userSettingPreferences, UserSettingPreferences.themeMusicVolume)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_theme_music_volume)) },
				captionContent = { Text("$themeMusicVolume%") },
				enabled = themeMusicEnabled,
				onClick = { router.push(Routes.MOONFIN_THEME_MUSIC_VOLUME) }
			)
		}
	}
}
