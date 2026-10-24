package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.RecordingStatus
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The Recordings screen's rows and the Schedule's days (#203): recordings carry no StartDate, so
 * the air time comes from the file name Jellyfin writes; every recording lands in one group.
 */
class RecordingsLayoutTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 24, 9, 0)
	val minute = 600_000_000L

	fun rec(
		name: String,
		aired: LocalDateTime?,
		genres: List<String> = emptyList(),
		minutes: Long? = 120,
		played: Boolean = false,
		position: Long = 0,
		type: BaseItemKind = BaseItemKind.MOVIE,
		seriesName: String? = null,
		path: String? = aired?.let {
			"/config/data/livetv/recordings/$name/$name %04d_%02d_%02d_%02d_%02d_00.ts"
				.format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute)
		},
	) = BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		name = name,
		path = path,
		genres = genres,
		seriesName = seriesName,
		runTimeTicks = minutes?.let { it * minute },
		dateCreated = aired?.minusMinutes(5),
		userData = UserItemDataDto(
			playbackPositionTicks = position,
			playCount = if (played) 1 else 0,
			isFavorite = false,
			played = played,
			key = name,
			itemId = UUID.randomUUID(),
		),
	)

	test("air time comes from the file name, else when the file was made") {
		airStartFromPath("/config/data/livetv/recordings/Live  NHL Hockey/Live  NHL Hockey 2026_10_23_20_00_00.ts") shouldBe
			LocalDateTime.of(2026, 10, 23, 20, 0, 0)
		airStartFromPath("/recordings/Some Film (2019)/Some Film (2019).ts").shouldBeNull()
		airStartFromPath(null).shouldBeNull()
		airStartFromPath("/r/x 2026_13_40_20_00_00.ts").shouldBeNull()

		val noTimeInName = BaseItemDto(
			id = UUID.randomUUID(), type = BaseItemKind.MOVIE, path = "/r/Film (2019).ts",
			dateCreated = LocalDateTime.of(2026, 10, 22, 19, 55),
		)
		recordingAirStart(noTimeInName) shouldBe LocalDateTime.of(2026, 10, 22, 19, 55)
	}

	test("the span runs from the file's start for the recording's length") {
		val item = rec("Game", LocalDateTime.of(2026, 10, 23, 20, 0), minutes = 219)
		recordingSpan(item) shouldBe (LocalDateTime.of(2026, 10, 23, 19, 55) to LocalDateTime.of(2026, 10, 23, 23, 34))
		recordingSpan(rec("No length", LocalDateTime.of(2026, 10, 23, 20, 0), minutes = null)).shouldBeNull()
	}

	test("every recording lands in exactly one group, newest first") {
		val habs = rec("Canadiens at Blackhawks", now.minusHours(13), genres = listOf("Sports"))
		val nhl = rec("Live: NHL Hockey", now.minusHours(14), genres = listOf("Sports"), played = true)
		val show1 = rec("Evening Show", now.minusDays(1))
		val show2 = rec("Evening Show", now.minusDays(2), played = true)
		val show3 = rec("evening show ", now.minusDays(9))
		val film = rec("Some Film", null, path = "/r/Some Film (2019)/Some Film (2019).ts")
		val oneOff = rec("Election Night", now.minusDays(3))
		val episode = rec("Pilot", now.minusDays(4), type = BaseItemKind.EPISODE, seriesName = "New Drama")
		val broken = rec("Game 7", now.minusDays(1), genres = listOf("Sports"), minutes = 0)
		val all = listOf(habs, nhl, show1, show2, show3, film, oneOff, episode, broken)

		val groups = groupRecordings(all.shuffled(), emptySet(), now)

		groups.sports shouldContainExactly listOf(habs, nhl)
		groups.shows.map { it.title } shouldContainExactly listOf("Evening Show", "New Drama")
		groups.shows.first().items shouldContainExactly listOf(show1, show2, show3)
		groups.movies shouldContainExactly listOf(film)
		groups.other shouldContainExactly listOf(oneOff)
		groups.needsAttention shouldContainExactly listOf(broken)
		groups.groupedCount shouldBe all.size
		groups.recordingNow.shouldBeEmpty()
	}

	test("Recently recorded: the last 7 days, unwatched, not failed; Recording now on top") {
		val live = rec("Maple Leafs at Bruins", now.minusMinutes(30), genres = listOf("Sports"), minutes = 0)
		val fresh = rec("Canadiens at Senators", now.minusDays(1), genres = listOf("Sports"))
		val watched = rec("Old Game", now.minusDays(2), genres = listOf("Sports"), played = true)
		val old = rec("Older Game", now.minusDays(8), genres = listOf("Sports"))
		val failed = rec("Short", now.minusDays(1), minutes = 0)

		val groups = groupRecordings(listOf(old, watched, fresh, live, failed), setOf(live.id), now)

		groups.recordingNow shouldContainExactly listOf(live)
		groups.recentlyRecorded shouldContainExactly listOf(fresh)
		// A recording in progress is short because it is still going, not because it failed.
		groups.sports shouldContainExactly listOf(live, fresh, watched, old)
		groups.needsAttention shouldContainExactly listOf(failed)
	}

	test("resume point only for a started, unwatched recording") {
		recordingResumeTicks(rec("a", now, position = 5 * minute)) shouldBe 5 * minute
		recordingResumeTicks(rec("b", now)).shouldBeNull()
		recordingResumeTicks(rec("c", now, position = 5 * minute, played = true)).shouldBeNull()
	}

	test("Delete only where Jellyfin allows it, never for a TMDB title (#69)") {
		val base = rec("Game", now)
		canOfferRecordingDelete(base.copy(canDelete = true)) shouldBe true
		canOfferRecordingDelete(base.copy(canDelete = false)) shouldBe false
		canOfferRecordingDelete(base.copy(canDelete = null)) shouldBe false
		canOfferRecordingDelete(base.copy(canDelete = true, providerIds = mapOf("Tmdb" to "603"))) shouldBe false
	}

	fun entry(name: String, start: LocalDateTime, hours: Long = 2, status: RecordingStatus = RecordingStatus.NEW, series: String? = null) =
		ScheduleEntry(
			item = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.PROGRAM, name = name),
			timerId = "t-$name",
			seriesTimerId = series,
			name = name,
			episodeTitle = null,
			channelName = "TSN 2",
			start = start,
			end = start.plusHours(hours),
			status = status,
		)

	test("the Schedule by day, earliest first; cancelled and finished left out") {
		val running = entry("Maple Leafs at Bruins", now.minusHours(1), hours = 3, status = RecordingStatus.IN_PROGRESS)
		val tonight = entry("Canadiens at Kings", now.withHour(20))
		val late = entry("Evening Show", now.withHour(23).withMinute(35), series = "s1")
		val tomorrow = entry("Bills at Patriots", now.plusDays(1).withHour(13))
		val cancelled = entry("Cancelled", now.withHour(21), status = RecordingStatus.CANCELLED)
		val over = entry("Over", now.minusHours(5), hours = 1)

		val days = scheduleDays(listOf(tomorrow, late, cancelled, tonight, over, running), now)

		days.map { it.date } shouldContainExactly listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25))
		days.map { it.daysFromToday } shouldContainExactly listOf(0L, 1L)
		days[0].entries shouldContainExactly listOf(running, tonight, late)
		days[1].entries shouldContainExactly listOf(tomorrow)
		running.isRecordingNow(now) shouldBe true
		tonight.isRecordingNow(now) shouldBe false
		late.isSeries shouldBe true
	}
})
