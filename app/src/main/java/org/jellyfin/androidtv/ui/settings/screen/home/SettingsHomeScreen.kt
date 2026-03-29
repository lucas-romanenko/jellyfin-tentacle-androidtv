package org.jellyfin.androidtv.ui.settings.screen.home

import androidx.compose.runtime.Composable
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListMessage
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn

@Composable
fun SettingsHomeScreen() {
	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text("HOME SCREEN") },
				headingContent = { Text("Configuration") },
				captionContent = { Text("The home screen layout is managed from the MediaHub dashboard. Changes made there apply to all clients.") },
			)
		}

		item {
			ListMessage { Text("Use the MediaHub dashboard to configure hero spotlight, playlist rows, and Jellyfin sections (Continue Watching, Next Up, etc.)") }
		}
	}
}
