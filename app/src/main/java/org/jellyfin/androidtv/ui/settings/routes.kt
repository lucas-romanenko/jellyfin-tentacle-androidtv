package org.jellyfin.androidtv.ui.settings

import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.jellyfin.androidtv.ui.settings.screen.SettingsDeveloperScreen
import org.jellyfin.androidtv.ui.settings.screen.SettingsMainScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayGridScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayImageSizeScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayImageTypeScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayScreen
import org.jellyfin.androidtv.ui.settings.screen.livetv.SettingsLiveTvGuideFiltersScreen
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

object Routes {
	const val MAIN = "/"
	const val LIBRARIES_DISPLAY = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}"
	const val LIBRARIES_DISPLAY_IMAGE_SIZE = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/image-size"
	const val LIBRARIES_DISPLAY_IMAGE_TYPE = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/image-type"
	const val LIBRARIES_DISPLAY_GRID = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/grid"
	const val LIVETV_GUIDE_FILTERS = "/livetv/guide/filters"
	const val DEVELOPER = "/developer"
}

val routes = mapOf<String, RouteComposable>(
	Routes.MAIN to {
		SettingsMainScreen()
	},
	Routes.LIBRARIES_DISPLAY to { context ->
		val itemId = context.parameters["itemId"]?.toUUIDOrNull()
		val displayPreferencesId = context.parameters["displayPreferencesId"]
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		val userId = context.parameters["userId"]?.toUUIDOrNull()
		
		if (itemId != null && displayPreferencesId != null && serverId != null && userId != null) {
			SettingsLibrariesDisplayScreen(
				itemId = itemId,
				displayPreferencesId = displayPreferencesId,
				serverId = serverId,
				userId = userId
			)
		}
	},
	Routes.LIBRARIES_DISPLAY_IMAGE_SIZE to { context ->
		val itemId = context.parameters["itemId"]?.toUUIDOrNull()
		val displayPreferencesId = context.parameters["displayPreferencesId"]
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		val userId = context.parameters["userId"]?.toUUIDOrNull()
		
		if (itemId != null && displayPreferencesId != null && serverId != null && userId != null) {
			SettingsLibrariesDisplayImageSizeScreen(
				itemId = itemId,
				displayPreferencesId = displayPreferencesId,
				serverId = serverId,
				userId = userId
			)
		}
	},
	Routes.LIBRARIES_DISPLAY_IMAGE_TYPE to { context ->
		val itemId = context.parameters["itemId"]?.toUUIDOrNull()
		val displayPreferencesId = context.parameters["displayPreferencesId"]
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		val userId = context.parameters["userId"]?.toUUIDOrNull()
		
		if (itemId != null && displayPreferencesId != null && serverId != null && userId != null) {
			SettingsLibrariesDisplayImageTypeScreen(
				itemId = itemId,
				displayPreferencesId = displayPreferencesId,
				serverId = serverId,
				userId = userId
			)
		}
	},
	Routes.LIBRARIES_DISPLAY_GRID to { context ->
		val itemId = context.parameters["itemId"]?.toUUIDOrNull()
		val displayPreferencesId = context.parameters["displayPreferencesId"]
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		val userId = context.parameters["userId"]?.toUUIDOrNull()
		
		if (itemId != null && displayPreferencesId != null && serverId != null && userId != null) {
			SettingsLibrariesDisplayGridScreen(
				itemId = itemId,
				displayPreferencesId = displayPreferencesId,
				serverId = serverId,
				userId = userId
			)
		}
	},
	Routes.LIVETV_GUIDE_FILTERS to {
		SettingsLiveTvGuideFiltersScreen()
	},
	Routes.DEVELOPER to {
		SettingsDeveloperScreen()
	},
)
