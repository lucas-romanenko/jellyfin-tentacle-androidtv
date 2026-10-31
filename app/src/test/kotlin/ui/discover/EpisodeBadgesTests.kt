package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.data.repository.SonarrEpisodesResponse

/** Where each episode of the picker plays from (#889). */
class EpisodeBadgesTests : FunSpec({
	val manage = EpisodePickerMode.MANAGE

	fun manageSource(source: String) =
		episodeBadges(manage, source, inVodList = false, hasFile = false, unaired = false)

	test("Manage Episodes shows the server's source") {
		manageSource("both") shouldBe EpisodeBadges(vod = true, downloaded = true, unaired = false)
		manageSource("download") shouldBe EpisodeBadges(vod = false, downloaded = true, unaired = false)
		manageSource("stream") shouldBe EpisodeBadges(vod = true, downloaded = false, unaired = false)
		manageSource("upcoming") shouldBe EpisodeBadges(vod = false, downloaded = false, unaired = true)
		manageSource("missing") shouldBe EpisodeBadges(vod = false, downloaded = false, unaired = false, missing = true)
	}

	test("the server's source wins over the lists") {
		// Sonarr counted the show's .strm as a file before #889: the source says it is a stream
		episodeBadges(manage, "stream", inVodList = true, hasFile = true, unaired = false) shouldBe
			EpisodeBadges(vod = true, downloaded = false, unaired = false)
	}

	test("an older server without source: the VOD list, hasFile and the air date") {
		episodeBadges(manage, null, inVodList = true, hasFile = true, unaired = false) shouldBe
			EpisodeBadges(vod = true, downloaded = true, unaired = false)
		episodeBadges(manage, null, inVodList = false, hasFile = true, unaired = false) shouldBe
			EpisodeBadges(vod = false, downloaded = true, unaired = false)
		episodeBadges(manage, null, inVodList = true, hasFile = false, unaired = false) shouldBe
			EpisodeBadges(vod = true, downloaded = false, unaired = false)
		episodeBadges(manage, null, inVodList = false, hasFile = false, unaired = true) shouldBe
			EpisodeBadges(vod = false, downloaded = false, unaired = true)
		episodeBadges(manage, null, inVodList = false, hasFile = false, unaired = false) shouldBe
			EpisodeBadges(vod = false, downloaded = false, unaired = false, missing = true)
	}

	test("an unknown source falls back to the lists") {
		episodeBadges(manage, "elsewhere", inVodList = false, hasFile = true, unaired = false) shouldBe
			EpisodeBadges(vod = false, downloaded = true, unaired = false)
	}

	test("Missing shows in Manage Episodes only") {
		for (mode in listOf(EpisodePickerMode.ADD_NEW, EpisodePickerMode.DOWNLOAD_MORE)) {
			episodeBadges(mode, "missing", inVodList = false, hasFile = false, unaired = false) shouldBe
				EpisodeBadges(vod = false, downloaded = false, unaired = false, missing = false)
			episodeBadges(mode, null, inVodList = false, hasFile = false, unaired = false).missing shouldBe false
		}
	}

	test("only an aired episode with no copy can be ticked") {
		manageSource("missing").selectable shouldBe true
		manageSource("both").selectable shouldBe false
		manageSource("download").selectable shouldBe false
		manageSource("stream").selectable shouldBe false
		manageSource("upcoming").selectable shouldBe false
	}
	test("the episode list parses with and without the #889 fields") {
		val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
		val old = json.decodeFromString<SonarrEpisodesResponse>(
			"""{"in_sonarr":true,"episodes":[{"id":1,"seasonNumber":1,"episodeNumber":2,"hasFile":true}]}"""
		).episodes.single()
		old.hasFile shouldBe true
		old.source shouldBe null
		old.downloaded shouldBe null
		val new = json.decodeFromString<SonarrEpisodesResponse>(
			"""{"in_sonarr":true,"episodes":[{"id":1,"seasonNumber":1,"episodeNumber":2,"hasFile":false,
			"downloaded":false,"stream":true,"source":"stream","sonarrHasFile":true}]}"""
		).episodes.single()
		new.source shouldBe "stream"
		new.stream shouldBe true
		new.sonarrHasFile shouldBe true
	}
})
