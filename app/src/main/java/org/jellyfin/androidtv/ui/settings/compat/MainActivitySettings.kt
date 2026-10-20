package org.jellyfin.androidtv.ui.settings.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun MainActivitySettings() {
	val viewModel = koinActivityViewModel<SettingsViewModel>()
	val visible by viewModel.visible.collectAsState()

	// Nothing until settings are first opened: the theme, router and dialog of the hidden panel
	// were composed in Home's first frame at every app start. Not state: `visible` already
	// recomposes this, and the flag only ever goes from false to true.
	val opened = remember { booleanArrayOf(false) }
	if (visible) opened[0] = true
	if (!opened[0]) return

	JellyfinTheme {
		ProvideRouter(routes, Routes.MAIN) {
			SettingsDialog(
				visible = visible,
				onDismissRequest = { viewModel.hide() }
			) {
				SettingsRouterContent()
			}
		}
	}
}
