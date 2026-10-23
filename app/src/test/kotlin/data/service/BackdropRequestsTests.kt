package org.jellyfin.androidtv.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Watching on the Sony: a crime documentary focused, Star Wars behind it. Only the latest
 * background request may reach the screen, and another title's picture doesn't stay (#14).
 */
class BackdropRequestsTests : FunSpec({
	test("a load that finishes after a newer request doesn't show") {
		val requests = BackdropRequests()
		val starWars = requests.start("star-wars")
		val crimeDoc = requests.start("crime-doc")

		requests.publish(crimeDoc) shouldBe true
		requests.publish(starWars) shouldBe false
		requests.shownKey shouldBe "crime-doc"
	}

	test("an older load finishing first doesn't show either") {
		val requests = BackdropRequests()
		val starWars = requests.start("star-wars")
		val crimeDoc = requests.start("crime-doc")

		requests.publish(starWars) shouldBe false
		requests.shownKey.shouldBeNull()
		requests.publish(crimeDoc) shouldBe true
		requests.shownKey shouldBe "crime-doc"
	}

	test("a clear wins over an older load") {
		val requests = BackdropRequests()
		val starWars = requests.start("star-wars")
		requests.publishNothing(requests.start(null)) shouldBe true

		requests.publish(starWars) shouldBe false
		requests.shownKey.shouldBeNull()
	}

	test("a load that found no picture leaves nothing shown, only if it is the latest") {
		val requests = BackdropRequests()
		requests.publish(requests.start("star-wars"))
		val noBackdrop = requests.start("crime-doc")
		val later = requests.start("comedy")

		requests.publishNothing(noBackdrop) shouldBe false
		requests.shownKey shouldBe "star-wars"
		requests.publishNothing(later) shouldBe true
		requests.shownKey.shouldBeNull()
	}

	test("the picture of an earlier request fades out when the latest one is slow") {
		val requests = BackdropRequests()
		requests.publish(requests.start("star-wars"))
		val crimeDoc = requests.start("crime-doc")

		requests.fadesOut(crimeDoc) shouldBe true
		requests.shownKey.shouldBeNull()
		// Then its picture arrives
		requests.publish(crimeDoc) shouldBe true
		requests.shownKey shouldBe "crime-doc"
	}

	test("no fade once the latest picture is on screen, or for a request no longer latest") {
		val requests = BackdropRequests()
		requests.publish(requests.start("star-wars"))
		val crimeDoc = requests.start("crime-doc")
		requests.publish(crimeDoc)
		requests.fadesOut(crimeDoc) shouldBe false
		requests.shownKey shouldBe "crime-doc"

		val comedy = requests.start("comedy")
		requests.start("drama")
		requests.fadesOut(comedy) shouldBe false
		requests.shownKey shouldBe "crime-doc"
	}

	test("asking again for what is on screen needs no load") {
		val requests = BackdropRequests()
		requests.alreadyShown("crime-doc") shouldBe false
		requests.publish(requests.start("crime-doc"))

		requests.alreadyShown("crime-doc") shouldBe true
		requests.isLatestKey("crime-doc") shouldBe true
		requests.alreadyShown("star-wars") shouldBe false
		requests.alreadyShown(null) shouldBe false
	}

	test("back to the card still on screen before the other one loaded: it stays, the other can't land") {
		val requests = BackdropRequests()
		requests.publish(requests.start("crime-doc"))
		val starWars = requests.start("star-wars")

		requests.isLatestKey("crime-doc") shouldBe false
		requests.alreadyShown("crime-doc") shouldBe true
		requests.publish(requests.start("crime-doc")) shouldBe true

		requests.publish(starWars) shouldBe false
		requests.shownKey shouldBe "crime-doc"
	}

	test("each request is the latest until the next one") {
		val requests = BackdropRequests()
		val first = requests.start("a")
		requests.isLatest(first) shouldBe true
		val second = requests.start("a")
		requests.isLatest(first) shouldBe false
		requests.isLatest(second) shouldBe true
	}
})
