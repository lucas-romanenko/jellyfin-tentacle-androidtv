package org.jellyfin.androidtv.ui.settings

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.navigation.RouteComposable
import org.jellyfin.androidtv.ui.settings.composable.SettingsNumericScreen
import org.jellyfin.androidtv.ui.settings.screen.SettingsDeveloperScreen
import org.jellyfin.androidtv.ui.settings.screen.SettingsMainScreen
import org.jellyfin.androidtv.ui.settings.screen.SettingsTelemetryScreen
import org.jellyfin.androidtv.ui.settings.screen.about.SettingsAboutScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationAutoSignInScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationPinCodeScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationServerScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationServerUserScreen
import org.jellyfin.androidtv.ui.settings.screen.authentication.SettingsAuthenticationSortByScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.SettingsCustomizationClockScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.SettingsCustomizationScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.SettingsCustomizationThemeScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.SettingsCustomizationWatchedIndicatorScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.subtitle.SettingsSubtitleTextStrokeColorScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.subtitle.SettingsSubtitlesBackgroundColorScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.subtitle.SettingsSubtitlesScreen
import org.jellyfin.androidtv.ui.settings.screen.customization.subtitle.SettingsSubtitlesTextColorScreen
import org.jellyfin.androidtv.ui.settings.screen.home.SettingsHomePosterSizeScreen
import org.jellyfin.androidtv.ui.settings.screen.home.SettingsHomeScreen
import org.jellyfin.androidtv.ui.settings.screen.home.SettingsHomeSectionScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayGridScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayImageSizeScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayImageTypeScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesDisplayScreen
import org.jellyfin.androidtv.ui.settings.screen.library.SettingsLibrariesScreen
import org.jellyfin.androidtv.ui.settings.screen.license.SettingsLicenseScreen
import org.jellyfin.androidtv.ui.settings.screen.license.SettingsLicensesScreen
import org.jellyfin.androidtv.ui.settings.screen.livetv.SettingsLiveTvGuideChannelOrderScreen
import org.jellyfin.androidtv.ui.settings.screen.livetv.SettingsLiveTvGuideFiltersScreen
import org.jellyfin.androidtv.ui.settings.screen.livetv.SettingsLiveTvGuideOptionsScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleBrowsingBlurScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleDetailsBlurScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleHomeRowsImageScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarColorScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarContentTypeScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarExcludedGenresScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarItemCountScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarOpacityScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleMediaBarSourceTypeScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleNavbarPositionScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleParentalControlsScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleSeasonalSurpriseScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleShuffleContentTypeScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleSyncPlayScreen
import org.jellyfin.androidtv.ui.settings.screen.tentacle.SettingsTentacleThemeMusicVolumeScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackAdvancedScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackAudioBehaviorScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackInactivityPromptScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackMaxBitrateScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackMaxResolutionScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackPlayerScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackPrerollsScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackRefreshRateSwitchingBehaviorScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackResumeSubtractDurationScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.SettingsPlaybackZoomModeScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.mediasegment.SettingsPlaybackMediaSegmentScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.mediasegment.SettingsPlaybackMediaSegmentsScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.nextup.SettingsPlaybackNextUpBehaviorScreen
import org.jellyfin.androidtv.ui.settings.screen.playback.nextup.SettingsPlaybackNextUpScreen
import org.jellyfin.androidtv.ui.settings.screen.screensaver.SettingsScreensaverAgeRatingScreen
import org.jellyfin.androidtv.ui.settings.screen.screensaver.SettingsScreensaverDimmingScreen
import org.jellyfin.androidtv.ui.settings.screen.screensaver.SettingsScreensaverModeScreen
import org.jellyfin.androidtv.ui.settings.screen.screensaver.SettingsScreensaverScreen
import org.jellyfin.androidtv.ui.settings.screen.screensaver.SettingsScreensaverTimeoutScreen
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

object Routes {
	const val MAIN = "/"
	const val AUTHENTICATION = "/authentication"
	const val AUTHENTICATION_FROM_LOGIN = "/authentication+login"
	const val AUTHENTICATION_SERVER = "/authentication/server/{serverId}"
	const val AUTHENTICATION_SERVER_USER = "/authentication/server/{serverId}/user/{userId}"
	const val AUTHENTICATION_SORT_BY = "/authentication/sort-by"
	const val AUTHENTICATION_AUTO_SIGN_IN = "/authentication/auto-sign-in"
	const val AUTHENTICATION_PIN_CODE = "/authentication/pin-code"
	const val CUSTOMIZATION = "/customization"
	const val CUSTOMIZATION_THEME = "/customization/theme"
	const val CUSTOMIZATION_CLOCK = "/customization/clock"
	const val CUSTOMIZATION_WATCHED_INDICATOR = "/customization/watch-indicators"
	const val CUSTOMIZATION_SCREENSAVER = "/customization/screensaver"
	const val CUSTOMIZATION_SCREENSAVER_TIMEOUT = "/customization/screensaver/timeout"
	const val CUSTOMIZATION_SCREENSAVER_AGE_RATING = "/customization/screensaver/age-rating"
	const val CUSTOMIZATION_SCREENSAVER_MODE = "/customization/screensaver/mode"
	const val CUSTOMIZATION_SCREENSAVER_DIMMING = "/customization/screensaver/dimming"
	const val CUSTOMIZATION_SUBTITLES = "/customization/subtitles"
	const val CUSTOMIZATION_SUBTITLES_TEXT_COLOR = "/customization/subtitles/text-color"
	const val CUSTOMIZATION_SUBTITLES_BACKGROUND_COLOR = "/customization/subtitles/background-color"
	const val CUSTOMIZATION_SUBTITLES_EDGE_COLOR = "/customization/subtitles/edge-color"
	const val LIBRARIES = "/libraries"
	const val LIBRARIES_DISPLAY = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}"
	const val LIBRARIES_DISPLAY_IMAGE_SIZE = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/image-size"
	const val LIBRARIES_DISPLAY_IMAGE_TYPE = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/image-type"
	const val LIBRARIES_DISPLAY_GRID = "/libraries/display/{itemId}/{displayPreferencesId}/{serverId}/{userId}/grid"
	const val HOME = "/home"
	const val HOME_SECTION = "/home/section/{index}"
	const val HOME_POSTER_SIZE = "/home/poster-size"
	const val HOME_ROWS_IMAGE_TYPE = "/home/rows-image-type"
	const val LIVETV_GUIDE_FILTERS = "/livetv/guide/filters"
	const val LIVETV_GUIDE_OPTIONS = "/livetv/guide/options"
	const val LIVETV_GUIDE_CHANNEL_ORDER = "/livetv/guide/channel-order"
	const val PLAYBACK = "/playback"
	const val PLAYBACK_PLAYER = "/playback/player"
	const val PLAYBACK_NEXT_UP = "/playback/next-up"
	const val PLAYBACK_NEXT_UP_BEHAVIOR = "/playback/next-up/behavior"
	const val PLAYBACK_INACTIVITY_PROMPT = "/playback/inactivity-prompt"
	const val PLAYBACK_PREROLLS = "/playback/prerolls"
	const val PLAYBACK_MEDIA_SEGMENTS = "/playback/media-segments"
	const val PLAYBACK_MEDIA_SEGMENT = "/playback/media-segments/{segmentType}"
	const val PLAYBACK_ADVANCED = "/playback/advanced"
	const val PLAYBACK_RESUME_SUBTRACT_DURATION = "/playback/resume-subtract-duration"
	const val PLAYBACK_MAX_BITRATE = "/playback/max-bitrate"
	const val PLAYBACK_MAX_RESOLUTION = "/playback/max-resolution"
	const val PLAYBACK_REFRESH_RATE_SWITCHING_BEHAVIOR = "/playback/refresh-rate-switching-behavior"
	const val PLAYBACK_ZOOM_MODE = "/playback/zoom-mode"
	const val PLAYBACK_AUDIO_BEHAVIOR = "/playback/audio-behavior"
	const val TENTACLE_NAVBAR_POSITION = "/tentacle/navbar-position"
	const val TENTACLE_SHUFFLE_CONTENT_TYPE = "/tentacle/shuffle-content-type"
	const val TENTACLE_MEDIA_BAR_SOURCE_TYPE = "/tentacle/media-bar-source-type"
	const val TENTACLE_MEDIA_BAR_EXCLUDED_GENRES = "/tentacle/media-bar-excluded-genres"
	const val TENTACLE_MEDIA_BAR_CONTENT_TYPE = "/tentacle/media-bar-content-type"
	const val TENTACLE_MEDIA_BAR_ITEM_COUNT = "/tentacle/media-bar-item-count"
	const val TENTACLE_MEDIA_BAR_OPACITY = "/tentacle/media-bar-opacity"
	const val TENTACLE_MEDIA_BAR_COLOR = "/tentacle/media-bar-color"
	const val TENTACLE_THEME_MUSIC_VOLUME = "/tentacle/theme-music-volume"
	const val TENTACLE_SEASONAL_SURPRISE = "/tentacle/seasonal-surprise"
	@Deprecated("Moved to HOME_ROWS_IMAGE_TYPE", replaceWith = ReplaceWith("HOME_ROWS_IMAGE_TYPE"))
	const val TENTACLE_HOME_ROWS_IMAGE = "/tentacle/home-rows-image"
	const val TENTACLE_DETAILS_BLUR = "/tentacle/details-blur"
	const val TENTACLE_BROWSING_BLUR = "/tentacle/browsing-blur"
	const val TENTACLE_PARENTAL_CONTROLS = "/tentacle/parental-controls"
	const val TENTACLE_SYNCPLAY = "/tentacle/syncplay"
	const val TENTACLE_SYNCPLAY_MIN_DELAY = "/tentacle/syncplay/min-delay-speed-to-sync"
	const val TENTACLE_SYNCPLAY_MAX_DELAY = "/tentacle/syncplay/max-delay-speed-to-sync"
	const val TENTACLE_SYNCPLAY_DURATION = "/tentacle/syncplay/speed-to-sync-duration"
	const val TENTACLE_SYNCPLAY_MIN_DELAY_SKIP = "/tentacle/syncplay/min-delay-skip-to-sync"
	const val TENTACLE_SYNCPLAY_EXTRA_OFFSET = "/tentacle/syncplay/extra-time-offset"
	const val SYNCPLAY = "/syncplay"
	const val TELEMETRY = "/telemetry"
	const val DEVELOPER = "/developer"
	const val ABOUT = "/about"
	const val LICENSES = "/licenses"
	const val LICENSE = "/license/{artifactId}"
}

val routes = mapOf<String, RouteComposable>(
	Routes.MAIN to {
		SettingsMainScreen()
	},
	Routes.AUTHENTICATION to {
		SettingsAuthenticationScreen(false)
	},
	Routes.AUTHENTICATION_FROM_LOGIN to {
		SettingsAuthenticationScreen(true)
	},
	Routes.AUTHENTICATION_SERVER to { context ->
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		if (serverId != null) {
			SettingsAuthenticationServerScreen(serverId = serverId)
		}
	},
	Routes.AUTHENTICATION_SERVER_USER to { context ->
		val serverId = context.parameters["serverId"]?.toUUIDOrNull()
		val userId = context.parameters["userId"]?.toUUIDOrNull()
		if (serverId != null && userId != null) {
			SettingsAuthenticationServerUserScreen(
				serverId = serverId,
				userId = userId
			)
		}
	},
	Routes.AUTHENTICATION_SORT_BY to {
		SettingsAuthenticationSortByScreen()
	},
	Routes.AUTHENTICATION_AUTO_SIGN_IN to {
		SettingsAuthenticationAutoSignInScreen()
	},
	Routes.AUTHENTICATION_PIN_CODE to {
		SettingsAuthenticationPinCodeScreen()
	},
	Routes.CUSTOMIZATION to {
		SettingsCustomizationScreen()
	},
	Routes.CUSTOMIZATION_THEME to {
		SettingsCustomizationThemeScreen()
	},
	Routes.CUSTOMIZATION_CLOCK to {
		SettingsCustomizationClockScreen()
	},
	Routes.CUSTOMIZATION_WATCHED_INDICATOR to {
		SettingsCustomizationWatchedIndicatorScreen()
	},
	Routes.CUSTOMIZATION_SCREENSAVER to {
		SettingsScreensaverScreen()
	},
	Routes.CUSTOMIZATION_SCREENSAVER_TIMEOUT to {
		SettingsScreensaverTimeoutScreen()
	},
	Routes.CUSTOMIZATION_SCREENSAVER_AGE_RATING to {
		SettingsScreensaverAgeRatingScreen()
	},
	Routes.CUSTOMIZATION_SCREENSAVER_MODE to {
		SettingsScreensaverModeScreen()
	},
	Routes.CUSTOMIZATION_SCREENSAVER_DIMMING to {
		SettingsScreensaverDimmingScreen()
	},
	Routes.CUSTOMIZATION_SUBTITLES to {
		SettingsSubtitlesScreen()
	},
	Routes.CUSTOMIZATION_SUBTITLES_TEXT_COLOR to {
		SettingsSubtitlesTextColorScreen()
	},
	Routes.CUSTOMIZATION_SUBTITLES_BACKGROUND_COLOR to {
		SettingsSubtitlesBackgroundColorScreen()
	},
	Routes.CUSTOMIZATION_SUBTITLES_EDGE_COLOR to {
		SettingsSubtitleTextStrokeColorScreen()
	},
	Routes.LIBRARIES to {
		SettingsLibrariesScreen()
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
	Routes.HOME to {
		SettingsHomeScreen()
	},
	Routes.HOME_SECTION to { context ->
		val index = context.parameters["index"]?.toIntOrNull()
		if (index != null) {
			SettingsHomeSectionScreen(index)
		}
	},
	Routes.HOME_POSTER_SIZE to {
		SettingsHomePosterSizeScreen()
	},
	Routes.HOME_ROWS_IMAGE_TYPE to {
		SettingsTentacleHomeRowsImageScreen()
	},
	Routes.LIVETV_GUIDE_FILTERS to {
		SettingsLiveTvGuideFiltersScreen()
	},
	Routes.LIVETV_GUIDE_OPTIONS to {
		SettingsLiveTvGuideOptionsScreen()
	},
	Routes.LIVETV_GUIDE_CHANNEL_ORDER to {
		SettingsLiveTvGuideChannelOrderScreen()
	},
	Routes.PLAYBACK to {
		SettingsPlaybackScreen()
	},
	Routes.PLAYBACK_PLAYER to {
		SettingsPlaybackPlayerScreen()
	},
	Routes.PLAYBACK_NEXT_UP to {
		SettingsPlaybackNextUpScreen()
	},
	Routes.PLAYBACK_NEXT_UP_BEHAVIOR to {
		SettingsPlaybackNextUpBehaviorScreen()
	},
	Routes.PLAYBACK_INACTIVITY_PROMPT to {
		SettingsPlaybackInactivityPromptScreen()
	},
	Routes.PLAYBACK_PREROLLS to {
		SettingsPlaybackPrerollsScreen()
	},
	Routes.PLAYBACK_MEDIA_SEGMENTS to {
		SettingsPlaybackMediaSegmentsScreen()
	},
	Routes.PLAYBACK_MEDIA_SEGMENT to { context ->
		val segmentType = context.parameters["segmentType"]?.let(MediaSegmentType::fromNameOrNull)
		if (segmentType != null) {
			SettingsPlaybackMediaSegmentScreen(segmentType = segmentType)
		}
	},
	Routes.PLAYBACK_ADVANCED to {
		SettingsPlaybackAdvancedScreen()
	},
	Routes.PLAYBACK_RESUME_SUBTRACT_DURATION to {
		SettingsPlaybackResumeSubtractDurationScreen()
	},
	Routes.PLAYBACK_MAX_BITRATE to {
		SettingsPlaybackMaxBitrateScreen()
	},
	Routes.PLAYBACK_MAX_RESOLUTION to {
		SettingsPlaybackMaxResolutionScreen()
	},
	Routes.PLAYBACK_REFRESH_RATE_SWITCHING_BEHAVIOR to {
		SettingsPlaybackRefreshRateSwitchingBehaviorScreen()
	},
	Routes.PLAYBACK_ZOOM_MODE to {
		SettingsPlaybackZoomModeScreen()
	},
	Routes.PLAYBACK_AUDIO_BEHAVIOR to {
		SettingsPlaybackAudioBehaviorScreen()
	},
	Routes.TENTACLE_NAVBAR_POSITION to {
		SettingsTentacleNavbarPositionScreen()
	},
	Routes.TENTACLE_SHUFFLE_CONTENT_TYPE to {
		SettingsTentacleShuffleContentTypeScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_SOURCE_TYPE to {
		SettingsTentacleMediaBarSourceTypeScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_EXCLUDED_GENRES to {
		SettingsTentacleMediaBarExcludedGenresScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_CONTENT_TYPE to {
		SettingsTentacleMediaBarContentTypeScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_ITEM_COUNT to {
		SettingsTentacleMediaBarItemCountScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_OPACITY to {
		SettingsTentacleMediaBarOpacityScreen()
	},
	Routes.TENTACLE_MEDIA_BAR_COLOR to {
		SettingsTentacleMediaBarColorScreen()
	},
	Routes.TENTACLE_THEME_MUSIC_VOLUME to {
		SettingsTentacleThemeMusicVolumeScreen()
	},
	Routes.TENTACLE_SEASONAL_SURPRISE to {
		SettingsTentacleSeasonalSurpriseScreen()
	},
	Routes.TENTACLE_HOME_ROWS_IMAGE to {
		SettingsTentacleHomeRowsImageScreen()
	},
	Routes.TENTACLE_DETAILS_BLUR to {
		SettingsTentacleDetailsBlurScreen()
	},
	Routes.TENTACLE_BROWSING_BLUR to {
		SettingsTentacleBrowsingBlurScreen()
	},
	Routes.TENTACLE_PARENTAL_CONTROLS to {
		SettingsTentacleParentalControlsScreen()
	},
	Routes.TENTACLE_SYNCPLAY to {
		SettingsTentacleSyncPlayScreen()
	},
	Routes.TENTACLE_SYNCPLAY_MIN_DELAY to {
		SettingsNumericScreen(
			route = Routes.TENTACLE_SYNCPLAY_MIN_DELAY,
			preference = UserPreferences.syncPlayMinDelaySpeedToSync,
			titleRes = R.string.pref_syncplay_min_delay_speed_to_sync,
			valueTemplate = R.string.pref_syncplay_min_delay_speed_to_sync_description,
			minValue = 10.0,
			maxValue = 1000.0,
			stepSize = 10.0,
		)
	},
	Routes.TENTACLE_SYNCPLAY_MAX_DELAY to {
		SettingsNumericScreen(
			route = Routes.TENTACLE_SYNCPLAY_MAX_DELAY,
			preference = UserPreferences.syncPlayMaxDelaySpeedToSync,
			titleRes = R.string.pref_syncplay_max_delay_speed_to_sync,
			valueTemplate = R.string.pref_syncplay_max_delay_speed_to_sync_description,
			minValue = 10.0,
			maxValue = 1000.0,
			stepSize = 10.0,
		)
	},
	Routes.TENTACLE_SYNCPLAY_DURATION to {
		SettingsNumericScreen(
			route = Routes.TENTACLE_SYNCPLAY_DURATION,
			preference = UserPreferences.syncPlaySpeedToSyncDuration,
			titleRes = R.string.pref_syncplay_speed_to_sync_duration,
			valueTemplate = R.string.pref_syncplay_speed_to_sync_duration_description,
			minValue = 500.0,
			maxValue = 5000.0,
			stepSize = 100.0,
		)
	},
	Routes.TENTACLE_SYNCPLAY_MIN_DELAY_SKIP to {
		SettingsNumericScreen(
			route = Routes.TENTACLE_SYNCPLAY_MIN_DELAY_SKIP,
			preference = UserPreferences.syncPlayMinDelaySkipToSync,
			titleRes = R.string.pref_syncplay_min_delay_skip_to_sync,
			valueTemplate = R.string.pref_syncplay_min_delay_skip_to_sync_description,
			minValue = 10.0,
			maxValue = 5000.0,
			stepSize = 10.0,
		)
	},
	Routes.TENTACLE_SYNCPLAY_EXTRA_OFFSET to {
		SettingsNumericScreen(
			route = Routes.TENTACLE_SYNCPLAY_EXTRA_OFFSET,
			preference = UserPreferences.syncPlayExtraTimeOffset,
			titleRes = R.string.pref_syncplay_extra_time_offset,
			valueTemplate = R.string.pref_syncplay_extra_time_offset_description,
			minValue = -1000.0,
			maxValue = 1000.0,
			stepSize = 10.0,
		)
	},
	Routes.SYNCPLAY to {
		SettingsTentacleSyncPlayScreen()
	},
	Routes.TELEMETRY to {
		SettingsTelemetryScreen()
	},
	Routes.DEVELOPER to {
		SettingsDeveloperScreen()
	},
	Routes.ABOUT to { context ->
		SettingsAboutScreen(context.parameters["fromLogin"] == "true")
	},
	Routes.LICENSES to {
		SettingsLicensesScreen()
	},
	Routes.LICENSE to { context ->
		val artifactId = context.parameters["artifactId"]
		if (artifactId != null) {
			SettingsLicenseScreen(artifactId = artifactId)
		}
	},
)
