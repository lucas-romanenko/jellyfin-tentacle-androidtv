package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.ScrollDirection
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** The guide's Favorites / All choice, its dates and its readable times. */
class GuideModelTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 21, 14, 56)
	val today = now.toLocalDate()

	context("Favorites / All, shared with the web guide") {
		test("the saved choice is read; nothing or anything unknown is Favorites") {
			GuideView.fromPrefs(mapOf("guideView" to "all")) shouldBe GuideView.ALL
			GuideView.fromPrefs(mapOf("guideView" to "favorites")) shouldBe GuideView.FAVORITES
			GuideView.fromPrefs(mapOf("guideView" to "ALL")) shouldBe GuideView.FAVORITES
			GuideView.fromPrefs(mapOf("guideView" to null)) shouldBe GuideView.FAVORITES
			GuideView.fromPrefs(emptyMap()) shouldBe GuideView.FAVORITES
			GuideView.fromPrefs(null) shouldBe GuideView.FAVORITES
		}

		test("the web's preference names: id tentacle-livetv, client tentacle, key guideView") {
			GuideView.PREFS_ID shouldBe "tentacle-livetv"
			GuideView.PREFS_CLIENT shouldBe "tentacle"
			GuideView.PREFS_KEY shouldBe "guideView"
			GuideView.ALL.prefValue shouldBe "all"
			GuideView.FAVORITES.prefValue shouldBe "favorites"
		}

		test("saving keeps the other custom prefs and the rest of the preferences") {
			val saved = DisplayPreferencesDto(
				id = "tentacle-livetv",
				client = "tentacle",
				rememberIndexing = false,
				primaryImageHeight = 0,
				primaryImageWidth = 0,
				customPrefs = mapOf("guideView" to "favorites", "other" to "kept"),
				scrollDirection = ScrollDirection.VERTICAL,
				showBackdrop = true,
				rememberSorting = false,
				sortOrder = SortOrder.ASCENDING,
				showSidebar = false,
			)
			val updated = withGuideView(saved, GuideView.ALL)
			updated.customPrefs shouldBe mapOf("guideView" to "all", "other" to "kept")
			updated.showBackdrop shouldBe true
			updated.scrollDirection shouldBe ScrollDirection.VERTICAL
			GuideView.fromPrefs(updated.customPrefs) shouldBe GuideView.ALL
		}

		test("with no preferences yet, saving makes them") {
			val made = withGuideView(null, GuideView.FAVORITES)
			made.id shouldBe "tentacle-livetv"
			made.client shouldBe "tentacle"
			made.customPrefs shouldBe mapOf("guideView" to "favorites")
		}
	}

	context("dates only as far as the guide data goes") {
		test("data ending tomorrow morning: Today and Tomorrow") {
			guideDays(now, LocalDateTime.of(2026, 10, 22, 5, 0)) shouldContainExactly
				listOf(today, today.plusDays(1))
		}

		test("data ending today, unknown, or already over: only today, so no date choice") {
			guideDays(now, LocalDateTime.of(2026, 10, 21, 23, 0)) shouldContainExactly listOf(today)
			guideDays(now, null) shouldContainExactly listOf(today)
			guideDays(now, now.minusHours(1)) shouldContainExactly listOf(today)
			guideDays(now, now) shouldContainExactly listOf(today)
		}

		test("data ending exactly at midnight doesn't add the next day") {
			guideDays(now, LocalDateTime.of(2026, 10, 23, 0, 0)) shouldContainExactly
				listOf(today, today.plusDays(1))
		}

		test("a week of data is a week of days, never Jellyfin's fixed two weeks past the data") {
			guideDays(now, LocalDateTime.of(2026, 10, 27, 6, 0)).size shouldBe 7
			guideDays(now, now.plusYears(1)).size shouldBe MAX_GUIDE_DAYS
		}

		test("paging ahead stops where the data stops; unknown data never blocks it") {
			hasGuideDataAfter(LocalDateTime.of(2026, 10, 22, 0, 0), LocalDateTime.of(2026, 10, 22, 5, 0)) shouldBe true
			hasGuideDataAfter(LocalDateTime.of(2026, 10, 22, 5, 0), LocalDateTime.of(2026, 10, 22, 5, 0)) shouldBe false
			hasGuideDataAfter(LocalDateTime.of(2026, 10, 22, 9, 0), null) shouldBe true
		}

		test("the furthest programme is asked for once, newest first, ids in the body") {
			val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
			val request = furthestProgramRequest(ids)
			request.channelIds shouldBe ids
			request.sortBy shouldBe listOf(ItemSortBy.START_DATE)
			request.sortOrder shouldBe listOf(SortOrder.DESCENDING)
			request.limit shouldBe 1
			furthestProgramRequest(null).channelIds shouldBe null
		}

		test("day labels: Today, Tomorrow, then weekday and date") {
			guideDayLabel(today, today, "Today", "Tomorrow", Locale.US) shouldBe "Today"
			guideDayLabel(today.plusDays(1), today, "Today", "Tomorrow", Locale.US) shouldBe "Tomorrow"
			guideDayLabel(LocalDate.of(2026, 10, 23), today, "Today", "Tomorrow", Locale.US) shouldBe "Fri 23"
		}
	}

	context("readable times") {
		val twelveHour = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

		test("AM/PM once when both ends share it") {
			formatTimeRange(now.withHour(14).withMinute(0), now.withHour(16).withMinute(30), twelveHour) shouldBe "2:00 – 4:30 PM"
		}

		test("both when they differ") {
			formatTimeRange(now.withHour(11).withMinute(30), now.withHour(13).withMinute(0), twelveHour) shouldBe "11:30 AM – 1:00 PM"
		}

		test("narrow no-break space before PM (newer Android) is handled too") {
			val narrow = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
			formatTimeRange(now.withHour(14).withMinute(0), now.withHour(16).withMinute(30), narrow) shouldBe "2:00 – 4:30 PM"
		}

		test("24-hour clocks stay as they are") {
			formatTimeRange(now.withHour(14).withMinute(0), now.withHour(16).withMinute(30), DateTimeFormatter.ofPattern("HH:mm")) shouldBe "14:00 – 16:30"
		}

		test("the detail line: time, channel and episode, without gaps for what's missing") {
			val s = { n: Int -> "S$n" }
			val e = { n: Int -> "E$n" }
			detailLine("9:00 – 11:00 AM", "AMC", episodeText(2, 5, "Pilot", s, e)) shouldBe "9:00 – 11:00 AM  ·  AMC  ·  S2 E5  ·  Pilot"
			detailLine("9:00 – 11:00 AM", "AMC", episodeText(null, null, null, s, e)) shouldBe "9:00 – 11:00 AM  ·  AMC"
			detailLine("9:00 – 11:00 AM", "", episodeText(0, 3, null, s, e)) shouldBe "9:00 – 11:00 AM  ·  E3"
			episodeText(null, null, " ", s, e) shouldBe null
		}

		test("the grid starts at the last whole or half hour, so now is inside it") {
			guideStartFor(now) shouldBe LocalDateTime.of(2026, 10, 21, 14, 30)
			guideStartFor(now.withMinute(10)) shouldBe LocalDateTime.of(2026, 10, 21, 14, 0)
		}

		test("progress through the programme on now; -1 when it isn't on") {
			val start = now.withMinute(30)
			programProgress(start, start.plusMinutes(60), start.plusMinutes(15)) shouldBe 0.25f
			programProgress(start, start.plusMinutes(60), start.minusMinutes(1)) shouldBe -1f
			programProgress(start, start.plusMinutes(60), start.plusMinutes(60)) shouldBe -1f
			programProgress(null, start, now) shouldBe -1f
		}

		test("the guide opens around the last watched channel when it is in the view") {
			val a = UUID.randomUUID()
			val b = UUID.randomUUID()
			indexOfOrZero(listOf(a, b), b) shouldBe 1
			indexOfOrZero(listOf(a, b), UUID.randomUUID()) shouldBe 0
			indexOfOrZero(listOf(a, b), null) shouldBe 0
			indexOfOrZero(emptyList<UUID>(), a) shouldNotBe -1
		}
	}
})
