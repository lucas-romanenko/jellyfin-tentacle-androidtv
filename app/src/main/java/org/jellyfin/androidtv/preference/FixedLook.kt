package org.jellyfin.androidtv.preference

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.leanback.widget.FocusHighlight

/**
 * The look every Tentacle TV has. Customising happens in Tentacle (Lucas, 2026-10-22: "any
 * customization should be done from tentacle"): the home rows and the hero come from there,
 * and the TV's own look options left Settings. Their preferences stay, with the fixed look as
 * their defaults; [forgetChoices] drops what someone picked on the TV before, once, so every
 * TV looks the same.
 */
object FixedLook {
	/** Focused cards and buttons: Tentacle's accent. */
	val FOCUS_COLOR = Color(0xFF6C63FF)

	/** A focused card grows slightly. */
	const val CARD_FOCUS_ZOOM = FocusHighlight.ZOOM_FACTOR_SMALL

	/** Background blur behind details and while browsing: light. */
	const val BLUR = 5

	private const val VERSION_KEY = "fixed_look_version"
	private const val VERSION = 1

	/**
	 * The removed options' stored choices (all in the default preferences, the store of
	 * [UserPreferences] and the global [UserSettingPreferences]). Without them each falls back
	 * to its default: backgrounds follow the selected item, watched markers and the clock on,
	 * navbar on top, posters (no series thumbnails), theme music off, the hero as Tentacle
	 * sends it, SyncPlay off, subtitles as the person's Jellyfin settings say, new versions
	 * announced.
	 */
	val forgottenKeys: List<String> = listOf(
		UserPreferences.posterSize,
		UserPreferences.backdropEnabled,
		UserPreferences.showShuffleButton,
		UserPreferences.showLibrariesInToolbar,
		UserPreferences.navbarPosition,
		UserPreferences.syncPlayEnabled,
		UserPreferences.shuffleContentType,
		UserPreferences.enableMultiServerLibraries,
		UserPreferences.enableFolderView,
		UserPreferences.clockBehavior,
		UserPreferences.watchedIndicatorBehavior,
		UserPreferences.seriesThumbnailsEnabled,
		UserPreferences.subtitlesDefaultToNone,
		UserPreferences.updateNotificationsEnabled,
		UserSettingPreferences.mediaBarEnabled,
		UserSettingPreferences.mediaBarSourceType,
		UserSettingPreferences.mediaBarContentType,
		UserSettingPreferences.mediaBarItemCount,
		UserSettingPreferences.mediaBarExcludedGenres,
		UserSettingPreferences.mediaBarOverlayOpacity,
		UserSettingPreferences.mediaBarOverlayColor,
		UserSettingPreferences.homeRowsUniversalOverride,
		UserSettingPreferences.homeRowsUniversalImageType,
		UserSettingPreferences.detailsBackgroundBlurAmount,
		UserSettingPreferences.browsingBackgroundBlurAmount,
		UserSettingPreferences.themeMusicEnabled,
		UserSettingPreferences.themeMusicVolume,
		UserSettingPreferences.themeMusicOnHomeRows,
		UserSettingPreferences.focusColor,
	).map { it.key } + "pref_card_focus_expansion"

	/** Forgets the stored choices of the removed options, once per [VERSION]. */
	fun forgetChoices(prefs: SharedPreferences) {
		if (prefs.getInt(VERSION_KEY, 0) >= VERSION) return
		prefs.edit {
			forgottenKeys.forEach { remove(it) }
			putInt(VERSION_KEY, VERSION)
		}
	}
}
