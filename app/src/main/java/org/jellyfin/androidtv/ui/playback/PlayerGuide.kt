package org.jellyfin.androidtv.ui.playback

import java.time.Duration
import java.time.LocalDateTime

/**
 * The guide shown over live TV (the player's guide button, Menu): its page is built ahead, hidden,
 * a few seconds after a channel starts ([PLAYER_GUIDE_PREWARM_MS]), from the channel list the app
 * already has, so the first open is as quick as the later ones.
 */
const val PLAYER_GUIDE_PREWARM_MS = 4_000L

/** First channel of the page: the playing channel near the middle (the first page when it is on it). */
fun playerGuidePageStart(playingIndex: Int, count: Int, pageSize: Int): Int {
	if (playingIndex < pageSize || count <= pageSize) return 0
	return (playingIndex - pageSize / 2).coerceIn(0, count - pageSize)
}

/** The page starts at the last half hour; once the next one begins, its first column is gone by. */
fun playerGuideStale(guideStart: LocalDateTime?, now: LocalDateTime): Boolean =
	guideStart == null || now.isAfter(guideStart.plusMinutes(30))

/** Milliseconds until [playerGuideStale] turns true (0 when it is already). */
fun msUntilPlayerGuideStale(guideStart: LocalDateTime?, now: LocalDateTime): Long {
	if (guideStart == null) return 0
	return Duration.between(now, guideStart.plusMinutes(30)).toMillis().coerceAtLeast(0) + 1
}
