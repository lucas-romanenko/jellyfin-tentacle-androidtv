package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.eventhandling.tentaclePushTopics
import org.jellyfin.sdk.model.api.RecordingStatus

/**
 * The guide's record icons follow recordings set or cancelled anywhere (this TV, the web,
 * Tentacle's teams and rules) without a reload: Jellyfin's timers by programme id.
 */
class GuideRecordMarksTests : FunSpec({
	fun timer(id: String, programId: String?, status: RecordingStatus, series: String? = null) =
		RecordTimer(id, programId, status == RecordingStatus.NEW || status == RecordingStatus.IN_PROGRESS, series)

	test("set and running timers mark their programme, by id without dashes") {
		val marks = recordMarks(listOf(
			timer("t1", "0E1F2A3B-0000-0000-0000-00000000AAAA", RecordingStatus.NEW),
			timer("t2", "0e1f2a3b000000000000000000000bbb", RecordingStatus.IN_PROGRESS, series = "s1"),
		))
		marks.keys shouldContainExactly setOf("0e1f2a3b00000000000000000000aaaa", "0e1f2a3b000000000000000000000bbb")
		marks.getValue("0e1f2a3b00000000000000000000aaaa").toList() shouldBe listOf("t1", null)
		marks.getValue("0e1f2a3b000000000000000000000bbb").toList() shouldBe listOf("t2", "s1")
	}

	test("cancelled, completed and programme-less timers mark nothing") {
		recordMarks(listOf(
			timer("t1", "aaaa", RecordingStatus.CANCELLED),
			timer("t2", "bbbb", RecordingStatus.COMPLETED),
			timer("t3", null, RecordingStatus.NEW),
		)).shouldBeEmpty()
	}

	test("Tentacle's recordings push is a topic like the others") {
		tentaclePushTopics(mapOf("ItemId" to "tentacle", "Tentacle" to "recordings")) shouldBe setOf("recordings")
	}
})
