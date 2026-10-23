package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.LiveTvApi
import org.jellyfin.sdk.model.api.DayOfWeek
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.TimerInfoDto
import java.time.LocalDateTime
import java.util.UUID

/**
 * Record on a programme's details page books that one programme (#194). Jellyfin's default timer
 * for a programme is a series timer ("any time, every day, this channel", matched by series id or
 * by title); posting it to /LiveTv/SeriesTimers recorded every airing with the same title.
 */
class ProgrammeRecordTests : FunSpec({
	val programmeId = UUID.fromString("00000001-0000-4000-8000-0000000000aa")
	val channelId = UUID.fromString("00000002-0000-4000-8000-0000000000bb")
	val start = LocalDateTime.of(2026, 10, 23, 19, 0)

	val defaults = SeriesTimerInfoDto(
		programId = programmeId.toString().replace("-", ""),
		channelId = channelId,
		name = "NHL Hockey",
		startDate = start,
		endDate = start.plusHours(3),
		prePaddingSeconds = 300,
		postPaddingSeconds = 1800,
		recordAnyTime = true,
		recordAnyChannel = false,
		days = DayOfWeek.entries,
	)

	test("Record books this one programme, not a series timer") {
		val api = mockk<LiveTvApi>(relaxed = true)
		coEvery { api.getDefaultTimer(programmeId.toString()) } returns Response(defaults, 200, emptyMap())
		val booked = slot<TimerInfoDto>()
		coEvery { api.createTimer(capture(booked)) } returns Response(Unit, 204, emptyMap())

		runBlocking { api.recordProgramme(programmeId) }

		coVerify(exactly = 0) { api.createSeriesTimer(any()) }
		coVerify(exactly = 1) { api.createTimer(any()) }
		booked.captured.programId shouldBe defaults.programId
		booked.captured.channelId shouldBe channelId
		booked.captured.startDate shouldBe start
		booked.captured.endDate shouldBe start.plusHours(3)
		booked.captured.prePaddingSeconds shouldBe 300
		booked.captured.postPaddingSeconds shouldBe 1800
	}
})
