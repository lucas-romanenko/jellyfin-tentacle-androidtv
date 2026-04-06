package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.data.service.pluginsync.PluginSyncService
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
import org.jellyfin.androidtv.util.supportsFeature
import org.koin.compose.koinInject
import org.moonfin.server.core.feature.ServerFeature

@Composable
fun SettingsPluginScreen() {
	val router = LocalRouter.current
	val coroutineScope = rememberCoroutineScope()
	val userPreferences = koinInject<UserPreferences>()
	val pluginSyncService = koinInject<PluginSyncService>()
	val serverRepository = koinInject<ServerRepository>()
	val currentServer by serverRepository.currentServer.collectAsState()
	val jellyseerrSupported = currentServer.supportsFeature(ServerFeature.JELLYSEERR)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_plugin_settings)) },
				captionContent = { Text(stringResource(R.string.pref_plugin_description)) },
			)
		}

		item {
			var pluginSyncEnabled by rememberPreference(userPreferences, UserPreferences.pluginSyncEnabled)
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_moonfin), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_plugin_sync_enable)) },
				captionContent = { Text(stringResource(R.string.pref_plugin_sync_description)) },
				trailingContent = { Checkbox(checked = pluginSyncEnabled) },
				onClick = {
					pluginSyncEnabled = !pluginSyncEnabled
					userPreferences[UserPreferences.pluginSyncAutoDetected] = true
					if (pluginSyncEnabled) {
						userPreferences[UserPreferences.pluginSyncEnabled] = true
						coroutineScope.launch {
							pluginSyncService.initialSync()
							pluginSyncService.configureJellyseerrProxy()
						}
					} else {
						pluginSyncService.unregisterChangeListeners()
					}
				}
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_grid), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_toolbar_customization)) },
				captionContent = { Text(stringResource(R.string.pref_toolbar_customization_description)) },
				onClick = { router.push(Routes.PLUGIN_TOOLBAR) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_house), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.home_section_settings)) },
				captionContent = { Text(stringResource(R.string.pref_home_section_settings_description)) },
				onClick = { router.push(Routes.PLUGIN_HOME) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_channel_bar), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_media_bar_title)) },
				captionContent = { Text(stringResource(R.string.pref_media_bar_settings_description)) },
				onClick = { router.push(Routes.PLUGIN_MEDIA_BAR) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_music_album), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_theme_music_title)) },
				captionContent = { Text(stringResource(R.string.pref_theme_music_settings_description)) },
				onClick = { router.push(Routes.PLUGIN_THEME_MUSIC) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_photo), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_appearance)) },
				captionContent = { Text(stringResource(R.string.pref_appearance_settings_description)) },
				onClick = { router.push(Routes.PLUGIN_APPEARANCE) }
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_star), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_ratings_title)) },
				captionContent = { Text(stringResource(R.string.pref_ratings_settings_description)) },
				onClick = { router.push(Routes.PLUGIN_RATINGS) }
			)
		}

		if (jellyseerrSupported) {
			item {
				ListButton(
					leadingContent = { Icon(painterResource(R.drawable.ic_jellyseerr_jellyfish), contentDescription = null) },
					headingContent = { Text(stringResource(R.string.jellyseerr_settings)) },
					captionContent = { Text(stringResource(R.string.jellyseerr_settings_description)) },
					onClick = { router.push(Routes.JELLYSEERR) }
				)
			}
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_parental_controls)) },
				captionContent = { Text(stringResource(R.string.pref_parental_controls_description)) },
				onClick = { router.push(Routes.MOONFIN_PARENTAL_CONTROLS) }
			)
		}
	}
}


