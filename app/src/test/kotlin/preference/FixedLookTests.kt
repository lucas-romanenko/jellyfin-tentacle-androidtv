package org.jellyfin.androidtv.preference

import android.content.SharedPreferences
import androidx.leanback.widget.FocusHighlight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.androidtv.preference.constant.NavbarPosition
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior

/**
 * The TV's look options left Settings (2026-10-22): their defaults are the one look every TV
 * has, and a choice someone made on the TV before is forgotten once.
 */
class FixedLookTests : FunSpec({
	test("the defaults are the fixed look") {
		UserSettingPreferences.detailsBackgroundBlurAmount.defaultValue shouldBe 5
		UserSettingPreferences.browsingBackgroundBlurAmount.defaultValue shouldBe 5
		UserPreferences.backdropEnabled.defaultValue shouldBe true
		UserPreferences.watchedIndicatorBehavior.defaultValue shouldBe WatchedIndicatorBehavior.ALWAYS
		UserPreferences.clockBehavior.defaultValue shouldBe ClockBehavior.ALWAYS
		UserPreferences.navbarPosition.defaultValue shouldBe NavbarPosition.TOP
		UserPreferences.seriesThumbnailsEnabled.defaultValue shouldBe false
		UserPreferences.subtitlesDefaultToNone.defaultValue shouldBe false
		UserSettingPreferences.themeMusicEnabled.defaultValue shouldBe false
		UserSettingPreferences.mediaBarEnabled.defaultValue shouldBe true
		FixedLook.CARD_FOCUS_ZOOM shouldBe FocusHighlight.ZOOM_FACTOR_SMALL
		FixedLook.FOCUS_COLOR.value shouldBe androidx.compose.ui.graphics.Color(0xFF6C63FF).value
	}

	test("the removed options' choices are forgotten") {
		FixedLook.forgottenKeys shouldContainAll listOf(
			"pref_card_focus_expansion",
			"pref_show_backdrop",
			"pref_clock_behavior",
			"pref_watched_indicator_behavior",
			"pref_navbar_position",
			"pref_enable_series_thumbnails",
			"detailsBackgroundBlurAmount",
			"browsingBackgroundBlurAmount",
			"themeMusicEnabled",
			"focus_color",
			"subtitles_default_to_none",
		)
	}

	test("forgets once, then leaves the store alone") {
		val editor = mockk<SharedPreferences.Editor>(relaxed = true)
		val fresh = mockk<SharedPreferences> {
			every { getInt("fixed_look_version", 0) } returns 0
			every { edit() } returns editor
		}
		FixedLook.forgetChoices(fresh)
		FixedLook.forgottenKeys.forEach { key -> verify(exactly = 1) { editor.remove(key) } }
		verify(exactly = 1) { editor.putInt("fixed_look_version", 1) }

		val done = mockk<SharedPreferences> {
			every { getInt("fixed_look_version", 0) } returns 1
		}
		FixedLook.forgetChoices(done)
		verify(exactly = 0) { done.edit() }
	}

	test("the screensaver switch and playback choices are kept") {
		FixedLook.forgottenKeys.contains(UserPreferences.screensaverInAppEnabled.key) shouldBe false
		FixedLook.forgottenKeys.contains(UserPreferences.maxBitrate.key) shouldBe false
		FixedLook.forgottenKeys.contains(UserPreferences.audioBehaviour.key) shouldBe false
	}
})
