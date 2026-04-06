package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsPluginRatingsScreen() {
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_plugin_settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_ratings_title)) },
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.pref_enable_additional_ratings)) }) }

		item {
			var enableAdditionalRatings by rememberPreference(userSettingPreferences, UserSettingPreferences.enableAdditionalRatings)
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_star), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_enable_additional_ratings)) },
				captionContent = { Text(stringResource(R.string.pref_enable_additional_ratings_description)) },
				trailingContent = { Checkbox(checked = enableAdditionalRatings) },
				onClick = { enableAdditionalRatings = !enableAdditionalRatings }
			)
		}

		item {
			var showRatingLabels by rememberPreference(userSettingPreferences, UserSettingPreferences.showRatingLabels)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_show_rating_labels)) },
				captionContent = { Text(stringResource(R.string.pref_show_rating_labels_description)) },
				trailingContent = { Checkbox(checked = showRatingLabels) },
				onClick = { showRatingLabels = !showRatingLabels }
			)
		}

		item { ListSection(headingContent = { Text(stringResource(R.string.pref_episode_ratings)) }) }

		item {
			var enableEpisodeRatings by rememberPreference(userSettingPreferences, UserSettingPreferences.enableEpisodeRatings)
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_star), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_episode_ratings)) },
				captionContent = { Text(stringResource(R.string.pref_episode_ratings_description)) },
				trailingContent = { Checkbox(checked = enableEpisodeRatings) },
				onClick = { enableEpisodeRatings = !enableEpisodeRatings }
			)
		}
	}
}
