package org.jellyfin.androidtv.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The self-updater's decisions (github flavor). */
class UpdateLogicTests : FunSpec({
	fun asset(name: String) = GitHubAsset(name = name, downloadUrl = "https://example.invalid/$name", size = 1)

	test("versions compare per semver, a leading v ignored") {
		(compareVersions("1.21.0", "1.20.2") > 0) shouldBe true
		(compareVersions("1.20.2", "1.21.0") < 0) shouldBe true
		compareVersions("1.21.0", "v1.21.0") shouldBe 0
		compareVersions("v1.21", "1.21.0") shouldBe 0
		(compareVersions("1.10.0", "1.9.9") > 0) shouldBe true
		(compareVersions("2.0.0", "1.99.99") > 0) shouldBe true
	}

	test("a pre-release is older than its final release") {
		(compareVersions("1.21.0-rc.1", "1.21.0") < 0) shouldBe true
		(compareVersions("1.21.0", "1.21.0-rc.1") > 0) shouldBe true
		(compareVersions("1.21.0-rc.1", "1.20.2") > 0) shouldBe true
		(compareVersions("1.21.0-rc.2", "1.21.0-rc.10") < 0) shouldBe true
		(compareVersions("1.21.0-beta.1", "1.21.0-rc.1") < 0) shouldBe true
		(compareVersions("1.21.0-rc", "1.21.0-rc.1") < 0) shouldBe true
		(compareVersions("0.0.0-dev.1", "0.0.1") < 0) shouldBe true
		compareVersions("1.21.0+abc", "1.21.0") shouldBe 0
	}

	test("only a strictly newer version is offered; an installed rc is offered the final release") {
		isNewerVersion("1.21.0", "1.21.0-rc.1") shouldBe true
		isNewerVersion("v1.21.0", "1.21.0") shouldBe false
		isNewerVersion("1.20.2", "1.21.0") shouldBe false
		isNewerVersion("1.21.1", "1.21.0") shouldBe true
	}

	test("the asset is the github release APK, never a debug one") {
		pickReleaseAsset(
			listOf(
				asset("tentacle-androidtv-v1.21.0-github-debug.apk"),
				asset(STABLE_RELEASE_ASSET),
				asset("tentacle-androidtv-v1.21.0-github-release.apk"),
			)
		)?.name shouldBe "tentacle-androidtv-v1.21.0-github-release.apk"
		pickReleaseAsset(listOf(asset("notes.txt"), asset(STABLE_RELEASE_ASSET)))?.name shouldBe STABLE_RELEASE_ASSET
		pickReleaseAsset(listOf(asset("tentacle-androidtv-v1.21.0-github-debug.apk"))) shouldBe null
		pickReleaseAsset(listOf(asset("tentacle-androidtv-v1.21.0-playstore-release.apk"))) shouldBe null
		pickReleaseAsset(listOf(asset("tentacle-v1.21.0-release.apk")))?.name shouldBe "tentacle-v1.21.0-release.apk"
		pickReleaseAsset(emptyList()) shouldBe null
	}

	test("a release without a title or url still decodes") {
		val body = """{"tag_name":"v1.22.0","name":null,"body":null,"assets":[
			{"name":"tentacle-androidtv-v1.22.0-github-release.apk","browser_download_url":"https://example.invalid/a.apk","size":5}
		]}"""
		val result = parseUpdateResponse(200, body, "1.21.0").shouldBeInstanceOf<UpdateCheckResult.Available>()
		result.info.version shouldBe "1.22.0"
		result.info.downloadUrl shouldBe "https://example.invalid/a.apk"
		result.info.releaseNotes shouldBe ""
		result.info.releaseUrl shouldBe null
	}

	test("up to date, newer and failures are told apart") {
		val same = """{"tag_name":"v1.21.0","assets":[]}"""
		parseUpdateResponse(200, same, "1.21.0") shouldBe UpdateCheckResult.UpToDate
		parseUpdateResponse(200, same, "1.22.0") shouldBe UpdateCheckResult.UpToDate
		parseUpdateResponse(403, "rate limited", "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.RATE_LIMITED)
		parseUpdateResponse(429, null, "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.RATE_LIMITED)
		parseUpdateResponse(404, null, "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.NOT_FOUND)
		parseUpdateResponse(null, null, "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK)
		parseUpdateResponse(502, null, "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK)
		parseUpdateResponse(200, "<html>", "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.BAD_RESPONSE)
		parseUpdateResponse(200, "{}", "1.21.0") shouldBe UpdateCheckResult.Failed(UpdateCheckFailure.BAD_RESPONSE)
		// A newer release whose APK is not attached yet is not "up to date"
		parseUpdateResponse(200, """{"tag_name":"v1.22.0","assets":[]}""", "1.21.0") shouldBe
			UpdateCheckResult.Failed(UpdateCheckFailure.BAD_RESPONSE)
	}

	test("debug builds and the playstore flavor never update themselves") {
		selfUpdateMode(otaEnabled = true, isDebug = false, applicationId = "org.jellyfin.tentacle") shouldBe SelfUpdateMode.ENABLED
		selfUpdateMode(otaEnabled = true, isDebug = true, applicationId = "org.jellyfin.tentacle.debug") shouldBe SelfUpdateMode.DEBUG_BUILD
		selfUpdateMode(otaEnabled = true, isDebug = false, applicationId = "org.jellyfin.tentacle.debug") shouldBe SelfUpdateMode.DEBUG_BUILD
		selfUpdateMode(otaEnabled = false, isDebug = true, applicationId = "org.jellyfin.tentacle.debug") shouldBe SelfUpdateMode.DISABLED
	}

	test("Later keeps that version quiet for 3 days; a newer version shows at once") {
		val day = 24L * 60 * 60 * 1000
		val at = 1_000_000_000_000L
		isUpdateSnoozed("1.22.0", "1.22.0", at, at + day) shouldBe true
		isUpdateSnoozed("1.22.0", "1.22.0", at, at + 3 * day) shouldBe false
		isUpdateSnoozed("1.22.1", "1.22.0", at, at + day) shouldBe false
		isUpdateSnoozed("1.22.0", "", at, at + day) shouldBe false
		isUpdateSnoozed("1.22.0", null, 0, at) shouldBe false
		// A clock set back doesn't snooze forever
		isUpdateSnoozed("1.22.0", "1.22.0", at, at - day) shouldBe false
	}

	test("Home checks when the last check the person saw is 6 hours old, not per process") {
		val hour = 60L * 60 * 1000
		val now = 100 * hour
		fun due(lastSeen: Long, nowMs: Long = now) = isHomeCheckDue("1.23.0", lastSeen, "", false, null, nowMs)
		// Never checked on screen (a process Android started in the background counts for nothing)
		due(0) shouldBe true
		due(now - 5 * hour) shouldBe false
		due(now - 6 * hour) shouldBe true
		due(now - 30 * hour) shouldBe true
		// A clock set back doesn't silence it
		due(now + hour) shouldBe true
	}

	test("Home checks at once when the background check found a newer version") {
		val hour = 60L * 60 * 1000
		val now = 100 * hour
		val seenJustNow = now - 60_000
		isHomeCheckDue("1.23.0", seenJustNow, "1.25.1", foundSnoozed = false, lastFailedMs = null, nowMs = now) shouldBe true
		// "Later" for that version: the 6-hour rule decides
		isHomeCheckDue("1.23.0", seenJustNow, "1.25.1", foundSnoozed = true, lastFailedMs = null, nowMs = now) shouldBe false
		// Already installed (the update went through): nothing found any more
		isHomeCheckDue("1.25.1", seenJustNow, "1.25.1", foundSnoozed = false, lastFailedMs = null, nowMs = now) shouldBe false
		isHomeCheckDue("1.25.1", seenJustNow, "1.25.0", foundSnoozed = false, lastFailedMs = null, nowMs = now) shouldBe false
		isHomeCheckDue("1.23.0", seenJustNow, "", foundSnoozed = false, lastFailedMs = null, nowMs = now) shouldBe false
	}

	test("after a check without an answer Home waits 15 minutes before asking GitHub again") {
		val minute = 60L * 1000
		val now = 1000 * minute
		isHomeCheckDue("1.23.0", 0, "", false, lastFailedMs = now - 5 * minute, nowMs = now) shouldBe false
		isHomeCheckDue("1.23.0", 0, "1.25.1", false, lastFailedMs = now - 5 * minute, nowMs = now) shouldBe false
		isHomeCheckDue("1.23.0", 0, "", false, lastFailedMs = now - 15 * minute, nowMs = now) shouldBe true
		isHomeCheckDue("1.23.0", 0, "", false, lastFailedMs = now + minute, nowMs = now) shouldBe true
	}

	test("download percent") {
		downloadPercent(50, 200) shouldBe 25
		downloadPercent(200, 200) shouldBe 100
		downloadPercent(300, 200) shouldBe 100
		downloadPercent(10, -1) shouldBe null
	}
})
