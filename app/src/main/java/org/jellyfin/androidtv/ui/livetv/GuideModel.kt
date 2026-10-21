@file:JvmName("GuideModel")

package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ScrollDirection
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

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

// Live updates: the guide follows favourite and lineup changes while it is open (GuideLiveUpdates).

/** A Jellyfin id as the server sends it, with or without dashes, in one form to compare. */
fun normalizeItemId(id: String): String = id.replace("-", "").lowercase(Locale.ROOT)

/** One entry of a UserDataChanged message: an item and its favourite flag now. */
data class GuideUserData(val itemId: String, val isFavorite: Boolean)

/** The message is this person's (the user ids may come with or without dashes). */
fun isCurrentUser(currentUserId: String?, messageUserId: String?): Boolean =
	currentUserId != null && messageUserId != null && normalizeItemId(currentUserId) == normalizeItemId(messageUserId)

/**
 * The favourite flags a UserDataChanged message changes among [channels]: channel id -> new flag.
 * Only the channels in the list whose flag differs; other items (a movie, a channel not shown)
 * and another person's message change nothing. A later entry for the same item wins.
 */
fun guideFavoriteFlips(
	channels: List<BaseItemDto>,
	currentUserId: String?,
	messageUserId: String?,
	items: List<GuideUserData>,
): Map<UUID, Boolean> {
	if (!isCurrentUser(currentUserId, messageUserId) || items.isEmpty()) return emptyMap()
	val byId = channels.associateBy { normalizeItemId(it.id.toString()) }
	val flips = LinkedHashMap<UUID, Boolean>()
	for (item in items) {
		val channel = byId[normalizeItemId(item.itemId)] ?: continue
		if (channel.isFavoriteChannel() != item.isFavorite) flips[channel.id] = item.isFavorite
		else flips.remove(channel.id)
	}
	return flips
}

fun BaseItemDto.isFavoriteChannel(): Boolean = userData?.isFavorite == true

/** This channel with its favourite flag set to [favorite]. */
fun BaseItemDto.withFavorite(favorite: Boolean): BaseItemDto = copy(
	userData = userData?.copy(isFavorite = favorite) ?: org.jellyfin.sdk.model.api.UserItemDataDto(
		playbackPositionTicks = 0,
		playCount = 0,
		isFavorite = favorite,
		played = false,
		key = id.toString(),
		itemId = id,
	),
)

/** [channels] with the [flips] applied, same order. */
fun withFavorites(channels: List<BaseItemDto>, flips: Map<UUID, Boolean>): List<BaseItemDto> =
	if (flips.isEmpty()) channels else channels.map { channel -> flips[channel.id]?.let(channel::withFavorite) ?: channel }

/**
 * What a UserDataChanged message does to the guide's list. [channels]: the new list to show (null
 * when nothing changes). [flips]: the favourite changes among the channels shown. [relist]: the
 * list must be read again, because the Favorites view gained a channel it doesn't hold (its name,
 * logo and place in the order come from the server).
 */
data class GuideUserDataUpdate(
	val channels: List<BaseItemDto>?,
	val flips: Map<UUID, Boolean>,
	val relist: Boolean,
)

/**
 * The guide's list after a UserDataChanged message. All: the stars change in place. Favorites:
 * an unstarred channel goes (also one unstarred in the guide itself a moment ago), and a starred
 * item not shown re-reads the list when it is a channel: one of [knownChannelIds] (the channels
 * loaded for All), or maybe one when those aren't known.
 */
fun guideUserDataUpdate(
	view: GuideView,
	channels: List<BaseItemDto>,
	currentUserId: String?,
	messageUserId: String?,
	items: List<GuideUserData>,
	knownChannelIds: Set<UUID>?,
): GuideUserDataUpdate {
	if (!isCurrentUser(currentUserId, messageUserId)) return GuideUserDataUpdate(null, emptyMap(), relist = false)
	val flips = guideFavoriteFlips(channels, currentUserId, messageUserId, items)
	val updated = withFavorites(channels, flips)
	if (view == GuideView.ALL) return GuideUserDataUpdate(updated.takeIf { flips.isNotEmpty() }, flips, relist = false)

	val shown = channels.mapTo(HashSet()) { normalizeItemId(it.id.toString()) }
	val known = knownChannelIds?.mapTo(HashSet()) { normalizeItemId(it.toString()) }
	val latest = LinkedHashMap<String, Boolean>()
	for (item in items) latest[normalizeItemId(item.itemId)] = item.isFavorite
	val relist = latest.any { (id, favorite) -> favorite && id !in shown && (known == null || id in known) }
	val favorites = favoritesOnly(updated)
	return GuideUserDataUpdate(favorites.takeIf { it != channels }, flips, relist)
}

/** The Favorites view's channels: the starred ones, order kept. */
fun favoritesOnly(channels: List<BaseItemDto>): List<BaseItemDto> = channels.filter { it.isFavoriteChannel() }

/** What changed between the list shown and a newer one, by channel id. */
data class GuideChannelDiff(
	val added: List<UUID>,
	val removed: List<UUID>,
	/** Kept channels whose row looks different: name, number, logo or star. */
	val changed: List<UUID>,
	/** The kept channels are in another order. */
	val reordered: Boolean,
) {
	val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !reordered
	val lineupChanged: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()
}

/** What a channel's row shows: a change here redraws its header. */
private fun BaseItemDto.rowLook() = listOf(name, number, imageTags?.get(ImageType.PRIMARY), isFavoriteChannel())

fun diffGuideChannels(old: List<BaseItemDto>, new: List<BaseItemDto>): GuideChannelDiff {
	val oldById = old.associateBy { it.id }
	val newIds = new.mapTo(HashSet()) { it.id }
	val added = new.filter { it.id !in oldById }.map { it.id }
	val removed = old.filter { it.id !in newIds }.map { it.id }
	val changed = new.mapNotNull { channel -> oldById[channel.id]?.takeIf { it.rowLook() != channel.rowLook() }?.let { channel.id } }
	val keptOld = old.map { it.id }.filter { it in newIds }
	val keptNew = new.map { it.id }.filter { it in oldById }
	return GuideChannelDiff(added, removed, changed, reordered = keptOld != keptNew)
}

/**
 * The rows the guide shows after a live change, as indexes into [newIds]: the same channels as
 * before ([shownIds], the page shown from [oldIds]) plus what came in between them. A page that
 * reached the top or the bottom of the old list still does in the new one, so a channel added
 * at either end of the Favorites shows. Null when none of the shown channels is left (the guide
 * then shows the page anew), or the new list is empty.
 */
fun guideLiveWindow(oldIds: List<UUID>, oldStart: Int, oldEnd: Int, shownIds: Collection<UUID>, newIds: List<UUID>): IntRange? {
	if (newIds.isEmpty()) return null
	val index = newIds.withIndex().associate { (i, id) -> id to i }
	val kept = shownIds.mapNotNull { index[it] }
	if (kept.isEmpty()) return null
	val start = if (oldStart <= 0) 0 else kept.min()
	val end = if (oldEnd >= oldIds.size - 1) newIds.size - 1 else kept.max()
	return start..end
}

/**
 * Where focus goes when the focused row's channel [focused] goes: the next row of [order] (the
 * rows shown, top to bottom) that stays, else the nearest one above. Null when none stays.
 */
fun nearestKeptRow(order: List<UUID>, focused: UUID, kept: Set<UUID>): UUID? {
	val at = order.indexOf(focused)
	if (at < 0) return order.firstOrNull { it in kept }
	return order.drop(at + 1).firstOrNull { it in kept } ?: order.take(at).lastOrNull { it in kept }
}

private const val LIVE_TV_VERSION_MARKER = "tentacle-livetv:"

/**
 * The Live TV version in a LibraryChanged message's CollectionFolders: Tentacle sends
 * "tentacle-livetv:<version>" after every Live TV setup run. Null when there is none.
 */
fun liveTvVersionFrom(collectionFolders: List<String>?): Long? =
	collectionFolders?.firstNotNullOfOrNull { entry ->
		entry.takeIf { it.startsWith(LIVE_TV_VERSION_MARKER) }?.removePrefix(LIVE_TV_VERSION_MARKER)?.toLongOrNull()
	}
