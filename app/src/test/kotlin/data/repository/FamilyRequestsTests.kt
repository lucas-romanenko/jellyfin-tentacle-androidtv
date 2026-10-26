package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Family members' requests that wait for approval, and problems in plain words. */
class FamilyRequestsTests : FunSpec({
	val sent = "Sent to Lucas to approve. You'll see it in Activity."

	test("a pending add is a request, not a failure") {
		val json = """{"added":0,"already_exists":0,"failed":0,"pending":1,"detail":"$sent"}"""
		val result = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<AddResult>(json)
		result.pending shouldBe 1
		addOutcome(result, "Radarr") shouldBe AddOutcome(sent, requested = true)
		addOutcome(AddResult(pending = 1), "Sonarr") shouldBe AddOutcome("Sent for approval", requested = true)
	}

	test("the other add answers keep their messages") {
		addOutcome(AddResult(added = 1), "Radarr") shouldBe AddOutcome("Added to Radarr!")
		addOutcome(AddResult(alreadyExists = 1), "Sonarr") shouldBe AddOutcome("Already in Sonarr")
		addOutcome(AddResult(failed = 1, detail = "No root folder"), "Radarr") shouldBe AddOutcome("No root folder")
		addOutcome(AddResult(failed = 1), "Radarr") shouldBe AddOutcome("Radarr rejected the request")
		addOutcome(AddResult(error = "timeout"), "Radarr") shouldBe AddOutcome("Error: timeout")
		addOutcome(AddResult(), "Radarr") shouldBe AddOutcome("Failed to add")
	}

	test("the add button reads Request when adds wait for approval") {
		val ask = RequestMode(mode = "ask", approver = "Lucas")
		addButtonLabel("Radarr", ask, adding = false, requested = false) shouldBe "Request"
		addButtonLabel("Radarr", ask, adding = true, requested = false) shouldBe "Sending..."
		addButtonLabel("Sonarr", ask, adding = false, requested = true) shouldBe "Requested"
		addButtonLabel("Radarr", RequestMode.DIRECT, adding = false, requested = false) shouldBe "Add to Radarr"
		addButtonLabel("Sonarr", RequestMode.DIRECT, adding = true, requested = false) shouldBe "Adding..."
		addButtonLabel("Sonarr", RequestMode.DIRECT, adding = false, requested = true) shouldBe "Requested"
	}

	test("request mode: ask only when the server says so; an older server or a failure is direct") {
		parseRequestMode(200, """{"mode":"ask","approver":"Lucas"}""") shouldBe RequestMode("ask", "Lucas")
		parseRequestMode(200, """{"mode":"ask","approver":"Lucas"}""").asks shouldBe true
		parseRequestMode(200, """{"mode":"direct"}""").asks shouldBe false
		parseRequestMode(404, "Not found").asks shouldBe false
		parseRequestMode(500, null).asks shouldBe false
		parseRequestMode(200, "<html>").asks shouldBe false
	}

	test("Live TV shows the server's plain words, else the app's own message") {
		val status = parseFamilyStatus(
			200,
			"""{"messages":[{"area":"requests","text":"Requests are paused."},
				{"area":"livetv","text":"Live TV is unavailable right now. Lucas knows and is on it."}]}""",
		)
		liveTvNotice(status, "Unable to load channels") shouldBe "Live TV is unavailable right now. Lucas knows and is on it."
		liveTvNotice(status, null) shouldBe "Live TV is unavailable right now. Lucas knows and is on it."
		familyStatusText(status, "requests") shouldBe "Requests are paused."
		familyStatusText(status, "library") shouldBe null
		liveTvNotice(parseFamilyStatus(200, """{"messages":[]}"""), "Unable to load channels") shouldBe "Unable to load channels"
		liveTvNotice(parseFamilyStatus(200, """{"messages":[{"area":"livetv","text":"  "}]}"""), null) shouldBe null
		parseFamilyStatus(404, null) shouldBe null
		parseFamilyStatus(200, "nope") shouldBe null
		liveTvNotice(null, "Unable to load channels") shouldBe "Unable to load channels"
	}

	test("the same notice shows once per 30 seconds; another one shows at once") {
		shouldShowNotice("Live TV is down", null, 0, 1_000) shouldBe true
		shouldShowNotice("Live TV is down", "Live TV is down", 1_000, 20_000) shouldBe false
		shouldShowNotice("Live TV is down", "Live TV is down", 1_000, 31_000) shouldBe true
		shouldShowNotice("Unable to load channels", "Live TV is down", 1_000, 2_000) shouldBe true
	}

	val down = "IPTV Bears is down right now (their server isn't answering) since 6:30 PM. " +
		"Not a problem with your account or your setup. Live TV comes back by itself."

	test("a newer server's Live TV message carries its kind, provider and times") {
		val status = parseFamilyStatus(
			200,
			"""{"messages":[{"area":"livetv","kind":"down","provider":"IPTV Bears",
				"since":"2026-10-25T18:30:00-04:00","title":"IPTV Bears is down right now",
				"text":"$down","severity":3}]}""",
		)
		val message = status!!.messages.single()
		message.kind shouldBe "down"
		message.provider shouldBe "IPTV Bears"
		message.since shouldBe "2026-10-25T18:30:00-04:00"
		message.until shouldBe null
		message.title shouldBe "IPTV Bears is down right now"
		message.text shouldBe down
	}

	test("an older server's message (area and text only) still reads, as a warning") {
		val status = parseFamilyStatus(200, """{"messages":[{"area":"livetv","text":"Live TV is unavailable right now. Lucas knows."}]}""")
		status!!.messages.single().kind shouldBe null
		liveTvBanner(status) shouldBe GuideBanner("Live TV is unavailable right now. Lucas knows.", GuideBannerTone.WARNING)
	}

	test("the guide's line: a warning for a problem, good news for back, a plain note for some channels") {
		fun tone(kind: String?) = liveTvBanner(FamilyStatus(listOf(FamilyStatusMessage("livetv", "Words", kind = kind))))?.tone
		listOf("down", "blocked", "account", "offline", "other", "DOWN", "something new").forEach { tone(it) shouldBe GuideBannerTone.WARNING }
		tone("back") shouldBe GuideBannerTone.BACK
		tone("channels") shouldBe GuideBannerTone.NEUTRAL
	}

	test("the guide's line is the first Live TV message; other areas and empty words don't count") {
		val status = FamilyStatus(
			listOf(
				FamilyStatusMessage("requests", "Requests are paused."),
				FamilyStatusMessage("livetv", "  ", kind = "down"),
				FamilyStatusMessage("LiveTV", " $down ", kind = "down"),
				FamilyStatusMessage("livetv", "A few channels aren't coming through.", kind = "channels"),
			),
		)
		liveTvBanner(status) shouldBe GuideBanner(down, GuideBannerTone.WARNING)
		liveTvBanner(FamilyStatus(listOf(FamilyStatusMessage("library", "Scanning.")))) shouldBe null
		liveTvBanner(FamilyStatus()) shouldBe null
		liveTvBanner(parseFamilyStatus(200, """{"messages":[]}""")) shouldBe null
		liveTvBanner(null) shouldBe null
	}

	test("no answer keeps the line; an answer replaces it, an empty one hides it") {
		val shown = GuideBanner(down, GuideBannerTone.WARNING)
		val back = "IPTV Bears is back: Live TV works again (it was down, 6:30–7:45 PM)."
		nextGuideBanner(shown, null) shouldBe shown
		nextGuideBanner(null, null) shouldBe null
		nextGuideBanner(shown, FamilyStatus()) shouldBe null
		nextGuideBanner(shown, FamilyStatus(listOf(FamilyStatusMessage("livetv", back, kind = "back")))) shouldBe
			GuideBanner(back, GuideBannerTone.BACK)
	}
})
