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
})
