package org.jellyfin.androidtv.ui.playback

import android.hardware.display.DisplayManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.androidtv.ui.playback.nextup.NextUpState
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingState

private const val NEVER = DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER
private const val SEAMLESS_ONLY = DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY
private const val ALWAYS = DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS
private const val UNKNOWN = DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN

private val DISABLED = RefreshRateSwitchingBehavior.DISABLED
private val SCALE_ON_DEVICE = RefreshRateSwitchingBehavior.SCALE_ON_DEVICE
private val SCALE_ON_TV = RefreshRateSwitchingBehavior.SCALE_ON_TV

// A Google TV Streamer on a 4K TV: 101, 102 and 105 as its display reported them (2026-10-24),
// the rest the usual HDMI list. The menus run at 105, 2160p60.
private val modes = listOf(
	DisplayModeInfo(1, 1920, 1080, 60f),
	DisplayModeInfo(2, 1920, 1080, 59.94006f),
	DisplayModeInfo(3, 1920, 1080, 50f),
	DisplayModeInfo(4, 1920, 1080, 25f),
	DisplayModeInfo(5, 1920, 1080, 24f),
	DisplayModeInfo(6, 1920, 1080, 23.976025f),
	DisplayModeInfo(8, 1280, 720, 60f),
	DisplayModeInfo(9, 720, 480, 59.94006f),
	DisplayModeInfo(101, 3840, 2160, 23.976025f),
	DisplayModeInfo(102, 3840, 2160, 24f),
	DisplayModeInfo(103, 3840, 2160, 25f),
	DisplayModeInfo(104, 3840, 2160, 50f),
	DisplayModeInfo(105, 3840, 2160, 60f),
	DisplayModeInfo(106, 3840, 2160, 59.94006f),
)
private val menus = modes.first { it.id == 105 }

private fun best(
	frameRate: Float?,
	behavior: RefreshRateSwitchingBehavior = SCALE_ON_DEVICE,
	width: Int? = 1920,
	height: Int? = 1080,
	interlaced: Boolean = false,
	available: List<DisplayModeInfo> = modes,
	current: DisplayModeInfo = menus,
) = bestDisplayMode(available, current, width, height, frameRate, interlaced, behavior)?.id

/** What the player asks the TV for when a video starts from the menus, with nothing asked yet. */
private fun requestAtStart(
	matchContentFrameRate: Int,
	frameRate: Float?,
	stored: RefreshRateSwitchingBehavior = DISABLED,
	isLiveTv: Boolean = false,
	interlaced: Boolean = false,
): Int? {
	val behavior = refreshRateSwitching(stored, matchContentFrameRate, isLiveTv)
	return nextDisplayModeRequest(0, menus.id, best(frameRate, behavior, interlaced = interlaced))
}

/** Refresh-rate switching follows the TV's "Match content frame rate" setting (#173 removed the app's own). */
class DisplayModeSwitchTests : FunSpec({
	test("23.976 fps with the TV on Always: the TV is asked for 23.976 Hz at its own resolution") {
		requestAtStart(ALWAYS, 23.976f) shouldBe 101
	}

	test("Never: nothing is asked for, whatever was chosen on the TV before") {
		requestAtStart(NEVER, 23.976f) shouldBe null
		requestAtStart(NEVER, 23.976f, stored = SCALE_ON_DEVICE) shouldBe null
		requestAtStart(NEVER, 23.976f, stored = SCALE_ON_TV) shouldBe null
	}

	test("an unknown frame rate asks for nothing") {
		requestAtStart(ALWAYS, null) shouldBe null
		requestAtStart(ALWAYS, 0f) shouldBe null
		// A variable or odd r_frame_rate matches no mode
		requestAtStart(ALWAYS, 1000f) shouldBe null
	}

	test("Seamless only, or no such setting (before Android 12), keeps the earlier choice: off unless picked") {
		requestAtStart(SEAMLESS_ONLY, 23.976f) shouldBe null
		requestAtStart(UNKNOWN, 23.976f) shouldBe null
		requestAtStart(SEAMLESS_ONLY, 23.976f, stored = SCALE_ON_DEVICE) shouldBe 101
		requestAtStart(UNKNOWN, 23.976f, stored = SCALE_ON_TV) shouldBe 6
	}

	test("Always keeps an earlier Scale on TV choice") {
		refreshRateSwitching(SCALE_ON_TV, ALWAYS, isLiveTv = false) shouldBe SCALE_ON_TV
		requestAtStart(ALWAYS, 23.976f, stored = SCALE_ON_TV) shouldBe 6
	}

	test("Live TV doesn't switch on Always alone: unknown or changing channel rates, a blackout every zap") {
		refreshRateSwitching(DISABLED, ALWAYS, isLiveTv = true) shouldBe DISABLED
		requestAtStart(ALWAYS, 50f, isLiveTv = true) shouldBe null
		// Unless switching was picked on the TV before (#173 kept that choice)
		requestAtStart(ALWAYS, 50f, stored = SCALE_ON_DEVICE, isLiveTv = true) shouldBe 104
		refreshRateSwitching(SCALE_ON_DEVICE, NEVER, isLiveTv = true) shouldBe DISABLED
	}

	test("the exact rate wins over its multiples; 24.000 is not 23.976") {
		best(23.976f) shouldBe 101
		best(24f) shouldBe 102
		best(25f) shouldBe 103
		best(50f) shouldBe 104
		best(59.94f) shouldBe 106
		best(60f) shouldBe 105
		best(29.97f) shouldBe 106
	}

	test("Scale on TV picks the video's own resolution") {
		best(23.976f, SCALE_ON_TV) shouldBe 6
		best(23.976f, SCALE_ON_TV, width = 3840, height = 2160) shouldBe 101
		best(50f, SCALE_ON_TV, width = 1280, height = 720) shouldBe 3
	}

	test("interlaced video is shown at its field rate: 25i at 50 Hz, never 25 Hz (jellyfin-androidtv#4527)") {
		best(25f, interlaced = true) shouldBe 104
		best(50f, interlaced = true) shouldBe 104
		best(29.97f, interlaced = true) shouldBe 106
		requestAtStart(ALWAYS, 25f, interlaced = true) shouldBe 104
	}

	test("no resolution downgrade, no SD modes") {
		best(23.976f, width = 3840, height = 2160, available = modes.filter { it.width < 3840 }) shouldBe null
		best(59.94f, SCALE_ON_TV, width = 720, height = 480) shouldBe 2
	}

	test("missing width or height asks for nothing instead of crashing") {
		best(23.976f, width = null) shouldBe null
		best(23.976f, height = null) shouldBe null
	}

	test("disabled asks for nothing") {
		best(23.976f, DISABLED) shouldBe null
	}

	test("24 fps on a TV with only 60 Hz stays in the menus' mode") {
		val sixty = modes.filter { it.refreshRate == 60f }
		val mode = best(24f, available = sixty)
		mode shouldBe 105
		nextDisplayModeRequest(0, menus.id, mode) shouldBe null
	}

	test("the request: switch once, keep it while the rate stays, give it back when nothing fits") {
		// From the menus to 23.976
		nextDisplayModeRequest(requested = 0, currentModeId = 105, bestModeId = 101) shouldBe 101
		// The next episode at the same rate: no second switch
		nextDisplayModeRequest(requested = 101, currentModeId = 101, bestModeId = 101) shouldBe null
		// Already in that mode without asking: nothing to ask
		nextDisplayModeRequest(requested = 0, currentModeId = 101, bestModeId = 101) shouldBe null
		// A different rate
		nextDisplayModeRequest(requested = 101, currentModeId = 101, bestModeId = 104) shouldBe 104
		// An item or channel whose rate is unknown doesn't keep the previous one's mode
		nextDisplayModeRequest(requested = 101, currentModeId = 101, bestModeId = null) shouldBe 0
		nextDisplayModeRequest(requested = 0, currentModeId = 105, bestModeId = null) shouldBe null
	}

	test("a switch delays the start until the TV has re-synced") {
		videoStartDelayMs(0, switchesDisplayMode = true) shouldBe DISPLAY_MODE_SWITCH_START_DELAY_MS
		videoStartDelayMs(5_000, switchesDisplayMode = true) shouldBe 5_000
		videoStartDelayMs(0, switchesDisplayMode = false) shouldBe 0
		videoStartDelayMs(500, switchesDisplayMode = false) shouldBe 500
	}

	test("leaving Next Up or Still Watching without playing on gives the TV its mode back") {
		NextUpState.PLAY_NEXT.keepsDisplayMode() shouldBe true
		NextUpState.CLOSE.keepsDisplayMode() shouldBe false
		NextUpState.INITIALIZED.keepsDisplayMode() shouldBe false
		NextUpState.NO_DATA.keepsDisplayMode() shouldBe false
		StillWatchingState.STILL_WATCHING.keepsDisplayMode() shouldBe true
		StillWatchingState.CLOSE.keepsDisplayMode() shouldBe false
		StillWatchingState.INITIALIZED.keepsDisplayMode() shouldBe false
		StillWatchingState.NO_DATA.keepsDisplayMode() shouldBe false
	}
})
