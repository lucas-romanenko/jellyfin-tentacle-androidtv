package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.util.supportsFeature
import org.koin.compose.koinInject
import org.moonfin.server.core.feature.ServerFeature

@Composable
fun SettingsPluginScreen() {
	val router = LocalRouter.current
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
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_grid), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_toolbar_customization)) },
				captionContent = { Text(stringResource(R.string.pref_toolbar_customization_description)) },
				onClick = { router.push(Routes.PLUGIN_TOOLBAR) }
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
