@file:JvmName("GuidePlaces")

package org.jellyfin.androidtv.ui.livetv

import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * Where the guide was when it was left (a channel played, a programme's page), so it comes back
 * as it was: the view, the hours page (the day), the channel page, the row at the top and how far
 * past its top the rows were scrolled, the time at the left edge (or "now" when the current time
 * was in view), and the channel that had focus. Channels are kept by id, so rows that came or
 * went meanwhile don't shift the place.
 */
data class GuidePlace(
	val view: GuideView,
	val guideStart: LocalDateTime,
	val pageStartId: UUID?,
	val topId: UUID?,
	val topOffset: Int,
	val leftTime: LocalDateTime,
	val atNow: Boolean,
	val focusId: UUID?,
	val lastPlayedAtLeave: UUID?,
)

/**
 * The hours page to show again: the one left when it was ahead of now (paged ahead, another
 * day), else the current half hour's (the page left has partly gone by).
 */
fun placeGuideStart(place: GuidePlace, now: LocalDateTime): LocalDateTime {
	val current = guideStartFor(now)
	return if (place.guideStart.isAfter(current)) place.guideStart else current
}

/** The left edge was at or before the current time: the guide comes back at the current time. */
fun placeAtNow(leftTime: LocalDateTime, now: LocalDateTime): Boolean = !leftTime.isAfter(now)

/** The horizontal scroll that puts [GuidePlace.leftTime] at the left edge of the page from [guideStart]. */
fun placeScrollX(place: GuidePlace, guideStart: LocalDateTime, pxPerMinute: Int): Int {
	if (place.atNow) return 0
	val minutes = Duration.between(guideStart, place.leftTime).toMinutes()
	return (minutes * pxPerMinute).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * The channel to focus: the one played last when it changed while the guide was away (picked
 * in the guide, or Channel Up / Down in the player), else the one that had focus.
 */
fun placeFocus(place: GuidePlace, lastPlayed: UUID?): UUID? =
	if (lastPlayed != null && lastPlayed != place.lastPlayedAtLeave) lastPlayed else place.focusId ?: lastPlayed

/**
 * The first channel of the page to show: the page left, when it has the channel to focus (or that
 * channel isn't in the list); null when it doesn't, and the page goes around that channel as
 * before.
 */
fun placePageStart(ids: List<UUID>, place: GuidePlace, focusId: UUID?, pageSize: Int): Int? {
	val start = place.pageStartId?.let(ids::indexOf) ?: -1
	if (start < 0) return null
	val focus = focusId?.let(ids::indexOf) ?: -1
	return if (focus < 0 || focus in start until start + pageSize) start else null
}

/**
 * The vertical scroll: the row that was at the top, as far past its top as before; when the
 * channel to focus is then not fully in view, moved just enough to show it (Channel Up to the
 * next channel moves the rows one row at most), or that channel in the middle when it is far
 * away. Row tops are null when the row isn't on this page.
 */
fun placeScrollY(topRowTop: Int?, topOffset: Int, focusRowTop: Int?, rowHeight: Int, viewHeight: Int): Int {
	val y = if (topRowTop != null) (topRowTop + topOffset).coerceAtLeast(0) else 0
	if (focusRowTop == null) return y
	return when {
		focusRowTop + rowHeight <= y - viewHeight || focusRowTop >= y + 2 * viewHeight ->
			(focusRowTop - (viewHeight - rowHeight) / 2).coerceAtLeast(0)
		focusRowTop < y -> focusRowTop
		focusRowTop + rowHeight > y + viewHeight -> focusRowTop + rowHeight - viewHeight
		else -> y
	}
}
