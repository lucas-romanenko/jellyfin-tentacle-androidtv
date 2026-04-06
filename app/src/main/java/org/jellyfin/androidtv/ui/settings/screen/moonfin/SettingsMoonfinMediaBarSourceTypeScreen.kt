package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.koin.compose.koinInject

@Composable
fun SettingsMoonfinMediaBarSourceTypeScreen() {
	val router = LocalRouter.current
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val userPreferences = koinInject<UserPreferences>()
	var mediaBarSourceType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarSourceType)
	val pluginSyncEnabled = userPreferences[UserPreferences.pluginSyncEnabled]

	val options = buildList {
		add("local" to stringResource(R.string.pref_media_bar_source_local))
		if (pluginSyncEnabled) {
			add("plugin" to stringResource(R.string.pref_media_bar_source_plugin))
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_media_bar_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_media_bar_source_type)) },
				captionContent = { Text(stringResource(R.string.pref_media_bar_source_type_summary)) },
			)
		}

		items(options) { (value, label) ->
			ListButton(
				headingContent = { Text(label) },
				trailingContent = { RadioButton(checked = mediaBarSourceType == value) },
				onClick = {
					mediaBarSourceType = value
					router.back()
				}
			)
		}
	}
}
