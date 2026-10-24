package org.jellyfin.androidtv.ui.itemdetail.v2

import androidx.annotation.DrawableRes
import org.jellyfin.androidtv.R

/** The icon in the middle of the trailer screen after play / pause, and how long it stays (null: until playback goes on). */
data class TrailerPlayPauseIcon(@DrawableRes val icon: Int, val hideAfterMs: Long?)

const val TRAILER_PLAY_ICON_MS = 1_500L

/**
 * A YouTube trailer has no timeline (#205): paused, it shows a pause icon until it plays again,
 * so the still frame doesn't look frozen; playing again shows a play icon for a moment.
 */
fun trailerPlayPauseIcon(playing: Boolean): TrailerPlayPauseIcon =
	if (playing) TrailerPlayPauseIcon(R.drawable.ic_play, TRAILER_PLAY_ICON_MS)
	else TrailerPlayPauseIcon(R.drawable.ic_pause, null)
