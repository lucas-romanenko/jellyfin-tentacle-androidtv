package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.R
import java.time.LocalDateTime

/**
 * The Record button on a programme's details page (#193): one OK on a booked programme cancelled
 * its recording without a question, and Jellyfin's cancel also stops a recording in progress.
 * Cancelling asks first, like Cancel series on the same page.
 */
class ProgrammeRecordClickTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 23, 19, 0)
	val timerId = "6c6f6e676d61746368000000000000a1"

	class Clicks {
		val calls = mutableListOf<String>()
		val questions = mutableListOf<Pair<Int, () -> Unit>>()

		fun click(timerId: String?, startDate: LocalDateTime?, now: LocalDateTime) = programmeRecordClick(
			timerId = timerId,
			startDate = startDate,
			now = now,
			book = { calls += "book" },
			confirm = { message, onYes -> questions += message to onYes },
			cancel = { calls += "cancel $it" },
		)
	}

	test("one OK on Record of a booked programme does not cancel the recording") {
		val clicks = Clicks()
		clicks.click(timerId, now.plusHours(2), now)

		clicks.calls.shouldBeEmpty()
		clicks.questions.map { it.first } shouldContainExactly listOf(R.string.msg_cancel_recording)
	}

	test("Yes on the question cancels the booked recording") {
		val clicks = Clicks()
		clicks.click(timerId, now.plusHours(2), now)
		clicks.questions.single().second()

		clicks.calls shouldContainExactly listOf("cancel $timerId")
	}

	test("a programme recording now says cancelling stops the recording") {
		val clicks = Clicks()
		clicks.click(timerId, now.minusMinutes(30), now)

		clicks.calls.shouldBeEmpty()
		clicks.questions.single().first shouldBe R.string.msg_cancel_recording_in_progress
	}

	test("OK on Record of a programme that isn't booked books it at once") {
		val clicks = Clicks()
		clicks.click(null, now.plusHours(2), now)

		clicks.calls shouldContainExactly listOf("book")
		clicks.questions.shouldBeEmpty()
	}
})
