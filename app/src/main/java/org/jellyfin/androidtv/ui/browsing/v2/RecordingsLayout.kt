package org.jellyfin.androidtv.ui.browsing.v2

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.RecordingStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

// The Recordings and Schedule screens' logic as pure functions (#203): when a recording aired,
// which group it belongs to, and the schedule by day. The fragments only draw the result.

private val fileNameStart = Regex("""(\d{4})_(\d{2})_(\d{2})_(\d{2})_(\d{2})_(\d{2})""")

/**
 * When [item] aired: the programme start Jellyfin writes into a recording's file name
 * ("Title 2026_10_23_20_00_00.ts", the server's local time), else when the file was made.
 * Recordings have no StartDate, so this is the only "when" a finished one has.
 */
fun recordingAirStart(item: BaseItemDto): LocalDateTime? =
	airStartFromPath(item.path) ?: item.startDate ?: item.dateCreated

internal fun airStartFromPath(path: String?): LocalDateTime? {
	val name = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
	val match = fileNameStart.findAll(name).lastOrNull() ?: return null
	val (y, mo, d, h, mi, s) = match.destructured
	return runCatching {
		LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
	}.getOrNull()
}

/**
 * The time a recording ran on screen: from when the file started (pre-padding included) for its
 * length. Null without a length.
 */
fun recordingSpan(item: BaseItemDto): Pair<LocalDateTime, LocalDateTime>? {
	val start = item.dateCreated ?: recordingAirStart(item) ?: return null
	val ticks = item.runTimeTicks ?: return null
	if (ticks <= 0) return null
	return start to start.plusNanos(ticks * 100)
}

private const val TICKS_PER_MINUTE = 600_000_000L

/** Shorter than a minute: a recording that failed or stopped at once. */
fun isRecordingTooShort(item: BaseItemDto): Boolean {
	val ticks = item.runTimeTicks ?: return false
	return ticks < TICKS_PER_MINUTE
}

fun isRecordingSports(item: BaseItemDto): Boolean =
	item.isSports == true || item.genres.orEmpty().any { it.equals("Sports", true) || it.equals("Sport", true) }

/** A film: Jellyfin says so, or names the file "Title (Year)" without the air time (it does that for films). */
fun isRecordingMovie(item: BaseItemDto): Boolean =
	item.isMovie == true || (item.type == BaseItemKind.MOVIE && item.path != null && airStartFromPath(item.path) == null)

/** The title a show is grouped under: the series name, else the recording's own name. */
fun recordingShowTitle(item: BaseItemDto): String =
	item.seriesName?.takeIf { it.isNotBlank() } ?: item.name.orEmpty().trim()

private fun isRecordingEpisode(item: BaseItemDto): Boolean =
	item.type == BaseItemKind.EPISODE || item.isSeries == true || !item.seriesName.isNullOrBlank()

/** One card on the Shows row: every recording of one title, newest first. */
data class RecordedShow(
	val title: String,
	val items: List<BaseItemDto>,
) {
	val newest: BaseItemDto get() = items.first()
}

data class RecordedGroups(
	val recordingNow: List<BaseItemDto> = emptyList(),
	val recentlyRecorded: List<BaseItemDto> = emptyList(),
	val shows: List<RecordedShow> = emptyList(),
	val movies: List<BaseItemDto> = emptyList(),
	val sports: List<BaseItemDto> = emptyList(),
	val other: List<BaseItemDto> = emptyList(),
	val needsAttention: List<BaseItemDto> = emptyList(),
) {
	/** The recordings in the groups proper (Recording now and Recently recorded are extra views). */
	val groupedCount: Int
		get() = shows.sumOf { it.items.size } + movies.size + sports.size + other.size + needsAttention.size
}

const val RECENTLY_RECORDED_DAYS = 7L

/**
 * Sorts the recordings into the Recordings screen's rows, newest first in each. Every recording
 * lands in exactly one of Shows, Movies, Sports, Other and Needs attention, so a wrong guess can
 * never hide one; Recording now ([inProgressIds]) and Recently recorded (the last
 * [RECENTLY_RECORDED_DAYS] days, unwatched) show some of them a second time on top.
 *
 * A show is an episode, or any title that isn't sport recorded more than once: IPTV guides rarely
 * carry episode numbers, so Jellyfin files most recordings as films.
 */
fun groupRecordings(
	items: List<BaseItemDto>,
	inProgressIds: Set<UUID>,
	now: LocalDateTime,
): RecordedGroups {
	val newestFirst = items
		.distinctBy { it.id }
		.sortedWith(compareByDescending<BaseItemDto> { recordingAirStart(it) ?: LocalDateTime.MIN }.thenBy { it.name.orEmpty() })

	val inProgress = newestFirst.filter { it.id in inProgressIds }
	val recentFrom = now.minusDays(RECENTLY_RECORDED_DAYS)
	val recent = newestFirst.filter { item ->
		item.id !in inProgressIds &&
			item.userData?.played != true &&
			!isRecordingTooShort(item) &&
			recordingAirStart(item)?.isAfter(recentFrom) == true
	}

	val needsAttention = mutableListOf<BaseItemDto>()
	val sports = mutableListOf<BaseItemDto>()
	val movies = mutableListOf<BaseItemDto>()
	val showCandidates = mutableListOf<BaseItemDto>()
	for (item in newestFirst) {
		when {
			item.id !in inProgressIds && isRecordingTooShort(item) -> needsAttention += item
			isRecordingEpisode(item) -> showCandidates += item
			isRecordingSports(item) -> sports += item
			isRecordingMovie(item) -> movies += item
			else -> showCandidates += item
		}
	}

	// Titles seen once that aren't episodes stay single: "Other".
	val byTitle = showCandidates.groupBy { recordingShowTitle(it).lowercase() }
	val shows = mutableListOf<RecordedShow>()
	val other = mutableListOf<BaseItemDto>()
	for ((_, group) in byTitle) {
		if (group.size > 1 || group.any { isRecordingEpisode(it) }) shows += RecordedShow(recordingShowTitle(group.first()), group)
		else other += group
	}

	return RecordedGroups(
		recordingNow = inProgress,
		recentlyRecorded = recent,
		shows = shows.sortedWith(compareByDescending<RecordedShow> { recordingAirStart(it.newest) ?: LocalDateTime.MIN }.thenBy { it.title }),
		movies = movies,
		sports = sports,
		other = other,
		needsAttention = needsAttention,
	)
}

/** One booked programme on the Schedule screen. [item] is what OK opens (the programme). */
data class ScheduleEntry(
	val item: BaseItemDto,
	val timerId: String?,
	val seriesTimerId: String?,
	val name: String,
	val episodeTitle: String?,
	val channelName: String?,
	val start: LocalDateTime,
	val end: LocalDateTime?,
	val status: RecordingStatus?,
) {
	val isSeries: Boolean get() = seriesTimerId != null
	fun isRecordingNow(now: LocalDateTime): Boolean =
		status == RecordingStatus.IN_PROGRESS ||
			(status != RecordingStatus.CANCELLED && !start.isAfter(now) && end?.isAfter(now) == true)
}

data class ScheduleDay(
	val date: LocalDate,
	/** 0 today, 1 tomorrow, … (negative for a programme still running from yesterday). */
	val daysFromToday: Long,
	val entries: List<ScheduleEntry>,
)

/** The Schedule by day, earliest first; cancelled and finished bookings are left out. */
fun scheduleDays(entries: List<ScheduleEntry>, now: LocalDateTime): List<ScheduleDay> {
	val today = now.toLocalDate()
	return entries
		.filter { it.status != RecordingStatus.CANCELLED && it.status != RecordingStatus.COMPLETED }
		.filter { it.end == null || it.end.isAfter(now) || it.status == RecordingStatus.IN_PROGRESS }
		.sortedWith(compareBy<ScheduleEntry> { it.start }.thenBy { it.name })
		.groupBy { it.start.toLocalDate() }
		.map { (date, dayEntries) ->
			ScheduleDay(date, java.time.temporal.ChronoUnit.DAYS.between(today, date), dayEntries)
		}
}

/** Where a recording was left off, or null when it wasn't started (or is watched). */
fun recordingResumeTicks(item: BaseItemDto): Long? =
	item.userData?.playbackPositionTicks?.takeIf { it > 0 && item.userData?.played != true }

/** Delete is offered only where Jellyfin lets this person delete, and never for a TMDB title (#69). */
fun canOfferRecordingDelete(item: BaseItemDto): Boolean =
	item.canDelete == true && item.providerIds?.get("Tmdb").isNullOrBlank()

/** The Recordings screen's switch: Recorded | Scheduled | Series. */
enum class RecordingsTab { RECORDED, SCHEDULED, SERIES }

/** The parts this person sees: Scheduled and Series only for people who may manage recordings. */
fun recordingsTabs(canManage: Boolean): List<RecordingsTab> =
	if (canManage) RecordingsTab.entries else listOf(RecordingsTab.RECORDED)

/** The part a screen opens on: the one asked for ("Schedule" on Home), else Recorded. */
fun openingRecordingsTab(requested: String?, canManage: Boolean): RecordingsTab {
	val tab = RecordingsTab.entries.firstOrNull { it.name == requested } ?: RecordingsTab.RECORDED
	return if (tab in recordingsTabs(canManage)) tab else RecordingsTab.RECORDED
}
