@file:JvmName("DisplayModeSwitch")

package org.jellyfin.androidtv.ui.playback

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Window
import androidx.core.content.getSystemService
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.androidtv.ui.playback.nextup.NextUpState
import org.jellyfin.androidtv.ui.playback.stillwatching.StillWatchingState
import org.jellyfin.sdk.model.api.MediaStream
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Refresh-rate switching: the player asks the TV for the display mode that fits the video's
 * frame rate (the window's preferredDisplayModeId), so a 23.976 fps episode doesn't judder at
 * 60 Hz. Media3 asks by itself too (Surface.setFrameRate), but only for a seamless switch, and
 * over HDMI 60 to 23.976 Hz never is: only this request changes the mode.
 */

/**
 * How the player switches. The TV's own "Match content frame rate" setting decides (Android 12
 * and later; the app's option left Settings in #173): Always switches, keeping the TV's
 * resolution, Never doesn't. Seamless only, or no such setting, keeps the choice made on the TV
 * before ([stored], off by default); Media3 already makes the seamless switches.
 *
 * Live TV switches only on that earlier choice: a channel's frame rate is often unknown or
 * changes with the programme, and every zap would black the screen out.
 */
fun refreshRateSwitching(
	stored: RefreshRateSwitchingBehavior,
	matchContentFrameRate: Int,
	isLiveTv: Boolean,
): RefreshRateSwitchingBehavior = when (matchContentFrameRate) {
	DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> RefreshRateSwitchingBehavior.DISABLED
	DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS -> when {
		stored != RefreshRateSwitchingBehavior.DISABLED -> stored
		isLiveTv -> RefreshRateSwitchingBehavior.DISABLED
		else -> RefreshRateSwitchingBehavior.SCALE_ON_DEVICE
	}

	else -> stored
}

/** A display mode, as [Display.Mode] has it. */
data class DisplayModeInfo(
	val id: Int,
	val width: Int,
	val height: Int,
	val refreshRate: Float,
)

/**
 * The mode for a [width]x[height] video at [frameRate] fps, or null: switching is off, the
 * stream doesn't say, or no mode fits. Only HD modes at least the video's size, at the frame
 * rate, twice it or 2.5 times it; the exact rate wins, then the size: Scale on device keeps
 * [current]'s, Scale on TV the one closest to the video's. Interlaced video counts its fields:
 * 25i shows at 50 Hz, never 25 (jellyfin-androidtv#4527).
 */
fun bestDisplayMode(
	modes: List<DisplayModeInfo>,
	current: DisplayModeInfo,
	width: Int?,
	height: Int?,
	frameRate: Float?,
	interlaced: Boolean,
	behavior: RefreshRateSwitchingBehavior,
): DisplayModeInfo? {
	if (behavior == RefreshRateSwitchingBehavior.DISABLED) return null
	if (width == null || height == null || frameRate == null || frameRate <= 0f) return null

	val fps = if (interlaced && frameRate < 30f) frameRate * 2 else frameRate
	val sourceRate = (fps * 100).roundToInt()
	var best: DisplayModeInfo? = null
	var bestWeight = 0
	for (mode in modes) {
		// Skip non-HD modes and resolution downgrades
		if (mode.width < 1280 || mode.height < 720) continue
		if (mode.width < width || mode.height < height) continue

		val rate = (mode.refreshRate * 100).roundToInt()
		if (rate != sourceRate && rate != sourceRate * 2 && rate != (sourceRate * 2.5).roundToInt()) continue

		// -1: with Scale on device, the TV's own resolution beats a mode at the video's
		val keepsSize = behavior == RefreshRateSwitchingBehavior.SCALE_ON_DEVICE &&
			mode.width == current.width && mode.height == current.height
		val resolutionDifference = if (keepsSize) -1 else abs(mode.width - width)
		// 100,000 per term leaves room for 120 Hz and up (12,000)
		val weight = 100_000 - (rate - sourceRate) + 100_000 - resolutionDifference
		if (weight > bestWeight) {
			bestWeight = weight
			best = mode
		}
	}
	return best
}

/**
 * The preferredDisplayModeId the window asks for next, or null to leave [requested] as it is:
 * [bestModeId], or 0 (the TV's own choice) when nothing fits, so an item or channel without a
 * known rate doesn't keep the previous one's mode.
 */
fun nextDisplayModeRequest(requested: Int, currentModeId: Int, bestModeId: Int?): Int? {
	val wanted = when {
		bestModeId == null -> 0
		requested == 0 && bestModeId == currentModeId -> 0
		else -> bestModeId
	}
	return wanted.takeIf { it != requested }
}

/**
 * A mode switch blacks the screen out (and HDMI audio with it) for a second or two while the
 * TV re-syncs; Google's guidance is to start after it (developer.android.com, frame rate).
 */
const val DISPLAY_MODE_SWITCH_START_DELAY_MS = 2_000L

/** When the video starts: [preference] (the video start delay), at least the re-sync after a switch. */
fun videoStartDelayMs(preference: Long, switchesDisplayMode: Boolean): Long =
	if (switchesDisplayMode) maxOf(preference, DISPLAY_MODE_SWITCH_START_DELAY_MS) else preference

/** Next Up hands the mode to the next episode's player; Cancel or Back gives the TV its own. */
fun NextUpState.keepsDisplayMode() = this == NextUpState.PLAY_NEXT

/** The same for Still Watching: only Continue watching keeps the mode. */
fun StillWatchingState.keepsDisplayMode() = this == StillWatchingState.STILL_WATCHING

/** The TV's "Match content frame rate" setting, DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN before Android 12. */
fun matchContentFrameRate(context: Context): Int =
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		runCatching { context.getSystemService<DisplayManager>()?.matchContentFrameRateUserPreference }.getOrNull()
			?: DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
	} else {
		DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
	}

/** Asks for display mode [modeId] for [window], the whole app (one activity); 0 = the TV's own choice. */
fun requestDisplayMode(window: Window, modeId: Int) {
	val params = window.attributes
	if (params.preferredDisplayModeId == modeId) return
	params.preferredDisplayModeId = modeId
	window.attributes = params
}

private fun Display.Mode.info() = DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)

/**
 * Asks the TV for the mode that fits [video] as the player starts it. True when that switches
 * the mode now: the start waits for the TV ([videoStartDelayMs]).
 */
fun switchDisplayMode(
	activity: Activity,
	video: MediaStream?,
	stored: RefreshRateSwitchingBehavior,
	isLiveTv: Boolean,
): Boolean {
	val behavior = refreshRateSwitching(stored, matchContentFrameRate(activity), isLiveTv)
	@Suppress("DEPRECATION")
	val display = activity.windowManager.defaultDisplay
	val current = display.mode
	val best = bestDisplayMode(
		modes = display.supportedModes.map { it.info() },
		current = current.info(),
		width = video?.width,
		height = video?.height,
		frameRate = video?.realFrameRate,
		interlaced = video?.isInterlaced == true,
		behavior = behavior,
	)
	val requested = activity.window.attributes.preferredDisplayModeId
	val next = nextDisplayModeRequest(requested, current.modeId, best?.id) ?: return false
	Timber.i(
		"Display mode %d -> %d (now %d) for %sx%s@%s, %s",
		requested, next, current.modeId, video?.width, video?.height, video?.realFrameRate, behavior,
	)
	requestDisplayMode(activity.window, next)
	return next != 0 && next != current.modeId
}
