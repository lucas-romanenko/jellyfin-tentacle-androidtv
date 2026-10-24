package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R

/** Pausing a YouTube trailer shows a pause icon, playing it again a play icon for a moment (#205). */
class TrailerPlayPauseIconTests : FunSpec({
	test("paused: a pause icon that stays until playback goes on") {
		trailerPlayPauseIcon(playing = false) shouldBe TrailerPlayPauseIcon(R.drawable.ic_pause, null)
	}

	test("playing again: a play icon for a few seconds") {
		trailerPlayPauseIcon(playing = true) shouldBe TrailerPlayPauseIcon(R.drawable.ic_play, TRAILER_PLAY_ICON_MS)
	}
})
