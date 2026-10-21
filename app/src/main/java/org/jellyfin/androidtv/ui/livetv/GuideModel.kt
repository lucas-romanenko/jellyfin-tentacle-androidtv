@file:JvmName("GuideModel")

package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ScrollDirection
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Which channels the guide shows: this person's favourite channels or every channel they can see.
 * The choice lives in Jellyfin's display preferences (id [PREFS_ID], client [PREFS_CLIENT],
 * CustomPrefs [PREFS_KEY]), the same place Tentacle's web guide keeps it, so it follows the
 * person between the TV and the browser.
 */
enum class GuideView(val prefValue: String) {
	FAVORITES("favorites"),
	ALL("all");

	companion object {
		const val PREFS_ID = "tentacle-livetv"
		const val PREFS_CLIENT = "tentacle"
		const val PREFS_KEY = "guideView"

		/** The saved choice; Favorites when nothing (or something unknown) is saved. */
		@JvmStatic
		fun fromPrefs(customPrefs: Map<String, String?>?): GuideView =
			entries.firstOrNull { it.prefValue == customPrefs?.get(PREFS_KEY) } ?: FAVORITES
	}
}

/** [prefs] (or empty display preferences) with [view] saved, every other custom pref kept. */
fun withGuideView(prefs: DisplayPreferencesDto?, view: GuideView): DisplayPreferencesDto {
	val base = prefs ?: DisplayPreferencesDto(
		id = GuideView.PREFS_ID,
		client = GuideView.PREFS_CLIENT,
		primaryImageHeight = 0,
		primaryImageWidth = 0,
		customPrefs = emptyMap(),
		rememberIndexing = false,
		scrollDirection = ScrollDirection.HORIZONTAL,
		rememberSorting = false,
		showBackdrop = false,
		showSidebar = false,
		sortOrder = SortOrder.ASCENDING,
	)
	return base.copy(
		id = GuideView.PREFS_ID,
		client = GuideView.PREFS_CLIENT,
		customPrefs = base.customPrefs + (GuideView.PREFS_KEY to view.prefValue),
	)
}

/** Most days the date choice offers, whatever the guide data says. */
const val MAX_GUIDE_DAYS = 21

/**
 * The days the guide offers: today up to the day the guide data ends ([furthestEnd], the end of
 * the furthest programme), not a fixed two weeks. Data ending at midnight doesn't add the next
 * day. Without data past [now] (or unknown), only today.
 */
fun guideDays(now: LocalDateTime, furthestEnd: LocalDateTime?): List<LocalDate> {
	val today = now.toLocalDate()
	if (furthestEnd == null || !furthestEnd.isAfter(now)) return listOf(today)
	val last = furthestEnd.minusNanos(1).toLocalDate()
	return generateSequence(today) { it.plusDays(1) }
		.takeWhile { !it.isAfter(last) }
		.take(MAX_GUIDE_DAYS)
		.toList()
}

/** The next guide page (starting at [guideEnd]) has data: unknown data never blocks paging. */
fun hasGuideDataAfter(guideEnd: LocalDateTime, furthestEnd: LocalDateTime?): Boolean =
	furthestEnd == null || guideEnd.isBefore(furthestEnd)

/** "Today", "Tomorrow", then "Thu 23". */
fun guideDayLabel(day: LocalDate, today: LocalDate, todayLabel: String, tomorrowLabel: String, locale: Locale): String = when (day) {
	today -> todayLabel
	today.plusDays(1) -> tomorrowLabel
	else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + day.dayOfMonth
}

/** The guide's grid starts at the last whole or half hour, so the "now" line sits inside it. */
fun guideStartFor(time: LocalDateTime): LocalDateTime =
	time.withMinute(if (time.minute >= 30) 30 else 0).withSecond(0).withNano(0)

private val meridiem = Regex("^(.*?)[\\s\\u00A0\\u202F]?([AaPp]\\.?\\s?[Mm]\\.?)$")

/** "2:00 – 4:30 PM": AM/PM once when both ends share it; 24-hour clocks as "14:00 – 16:30". */
fun formatTimeRange(start: LocalDateTime, end: LocalDateTime, formatter: DateTimeFormatter): String {
	var from = formatter.format(start)
	val to = formatter.format(end)
	val a = meridiem.find(from)
	val b = meridiem.find(to)
	if (a != null && b != null && a.groupValues[2] == b.groupValues[2]) from = a.groupValues[1]
	return "$from – $to"
}

/** How far [now] is through a programme, 0..1; -1 when it isn't on now. */
fun programProgress(start: LocalDateTime?, end: LocalDateTime?, now: LocalDateTime): Float {
	if (start == null || end == null || !end.isAfter(start)) return -1f
	if (now.isBefore(start) || !now.isBefore(end)) return -1f
	val total = java.time.Duration.between(start, end).toMillis().toFloat()
	return java.time.Duration.between(start, now).toMillis() / total
}

/** Index of [lastChannel] in [ids] for the guide to open around (0 when it isn't there). */
fun <T> indexOfOrZero(ids: List<T>, lastChannel: T?): Int = ids.indexOf(lastChannel).coerceAtLeast(0)
