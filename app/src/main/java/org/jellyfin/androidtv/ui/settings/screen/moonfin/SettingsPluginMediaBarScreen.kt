package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.screen.customization.getMediaBarItemCountLabel
import org.jellyfin.androidtv.ui.settings.screen.customization.getMediaBarSourceTypeLabel
import org.jellyfin.androidtv.ui.settings.screen.customization.getOverlayColorLabel
import org.jellyfin.androidtv.ui.settings.screen.customization.getShuffleContentTypeLabel
import org.koin.compose.koinInject

@Composable
fun SettingsPluginMediaBarScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_plugin_settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_media_bar_title)) },
			)
		}

		item {
			var mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_enable)) },
				captionContent = { Text(stringResource(R.string.pref_media_bar_enable_summary)) },
				trailingContent = { Checkbox(checked = mediaBarEnabled) },
				onClick = { mediaBarEnabled = !mediaBarEnabled }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarSourceType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarSourceType)
			val pluginSyncEnabled = userPreferences[UserPreferences.pluginSyncEnabled]
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_source_type)) },
				captionContent = { Text(getMediaBarSourceTypeLabel(mediaBarSourceType)) },
				enabled = mediaBarEnabled && pluginSyncEnabled,
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_SOURCE_TYPE) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarSourceType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarSourceType)
			val pluginSyncEnabled = userPreferences[UserPreferences.pluginSyncEnabled]
			val excludedGenres = userSettingPreferences[UserSettingPreferences.mediaBarExcludedGenres]
			val excludedCount = try {
				val arr = Json.parseToJsonElement(excludedGenres.ifBlank { "[]" }) as? JsonArray
				arr?.size ?: 0
			} catch (_: Exception) { 0 }
			val caption = if (excludedCount == 0) stringResource(R.string.pref_media_bar_excluded_genres_none)
				else "$excludedCount excluded"
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres)) },
				captionContent = { Text(caption) },
				enabled = mediaBarEnabled && mediaBarSourceType == "plugin" && pluginSyncEnabled,
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_EXCLUDED_GENRES) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarSourceType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarSourceType)
			val pluginSyncEnabled = userPreferences[UserPreferences.pluginSyncEnabled]
			val mediaBarContentType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarContentType)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_content_type)) },
				captionContent = { Text(getShuffleContentTypeLabel(mediaBarContentType)) },
				enabled = mediaBarEnabled && !(mediaBarSourceType == "plugin" && pluginSyncEnabled),
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_CONTENT_TYPE) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarSourceType by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarSourceType)
			val pluginSyncEnabled = userPreferences[UserPreferences.pluginSyncEnabled]
			val mediaBarItemCount by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarItemCount)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_item_count)) },
				captionContent = { Text(getMediaBarItemCountLabel(mediaBarItemCount)) },
				enabled = mediaBarEnabled && !(mediaBarSourceType == "plugin" && pluginSyncEnabled),
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_ITEM_COUNT) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarOverlayOpacity by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarOverlayOpacity)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_overlay_opacity)) },
				captionContent = { Text("$mediaBarOverlayOpacity%") },
				enabled = mediaBarEnabled,
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_OPACITY) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			val mediaBarOverlayColor by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarOverlayColor)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_overlay_color)) },
				captionContent = { Text(getOverlayColorLabel(mediaBarOverlayColor)) },
				enabled = mediaBarEnabled,
				onClick = { router.push(Routes.MOONFIN_MEDIA_BAR_COLOR) }
			)
		}

		item {
			val mediaBarEnabled by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarEnabled)
			var trailerPreview by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarTrailerPreview)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_bar_trailer_preview)) },
				captionContent = { Text(stringResource(R.string.pref_media_bar_trailer_preview_summary)) },
				trailingContent = { Checkbox(checked = trailerPreview) },
				enabled = mediaBarEnabled,
				onClick = { trailerPreview = !trailerPreview }
			)
		}

		item {
			var episodePreview by rememberPreference(userSettingPreferences, UserSettingPreferences.episodePreviewEnabled)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_episode_preview)) },
				captionContent = { Text(stringResource(R.string.pref_episode_preview_summary)) },
				trailingContent = { Checkbox(checked = episodePreview) },
				onClick = { episodePreview = !episodePreview }
			)
		}

		item {
			var previewAudio by rememberPreference(userSettingPreferences, UserSettingPreferences.previewAudioEnabled)
			ListButton(
				headingContent = { Text(stringResource(R.string.pref_preview_audio)) },
				captionContent = { Text(stringResource(R.string.pref_preview_audio_summary)) },
				trailingContent = { Checkbox(checked = previewAudio) },
				onClick = { previewAudio = !previewAudio }
			)
		}
	}
}
