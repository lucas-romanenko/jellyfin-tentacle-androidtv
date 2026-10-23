package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.search.composable.searchCardSubtitle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.NameGuidPair
import java.util.UUID

/** Tentacle's YouTube videos show "YouTube · <channel>", never "Movie • 2026". */
class TentacleYouTubeTests : FunSpec({
	val strm = "/media/youtube/Veritasium/2026-10-01 Why Is It Hot [dQw4w9WgXcQ]/2026-10-01 Why Is It Hot [dQw4w9WgXcQ].strm"

	fun item(
		type: BaseItemKind = BaseItemKind.MOVIE,
		genres: List<String>? = null,
		tags: List<String>? = null,
		providerIds: Map<String, String?>? = null,
		path: String? = null,
		studio: String? = null,
	) = BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		productionYear = 2026,
		genres = genres,
		tags = tags,
		providerIds = providerIds,
		path = path,
		studios = studio?.let { listOf(NameGuidPair(name = it, id = UUID.randomUUID())) },
	)

	test("the channel folder of a Tentacle .strm") {
		youTubeChannelFromPath(strm) shouldBe "Veritasium"
		youTubeChannelFromPath("""D:\youtube\Some Channel\2025-01-02 A title [abc_DEF-123]\2025-01-02 A title [abc_DEF-123].strm""") shouldBe "Some Channel"
	}

	test("other paths have no channel") {
		youTubeChannelFromPath(null) shouldBe null
		youTubeChannelFromPath("/media/movies/Heat (1995)/Heat (1995).mkv") shouldBe null
		youTubeChannelFromPath("/media/movies/Heat (1995)/Heat (1995).strm") shouldBe null
		youTubeChannelFromPath("/media/youtube/Veritasium/2026-10-01 Why [dQw4w9WgXcQ]/video.mkv") shouldBe null
		youTubeChannelFromPath("2026-10-01 Why [dQw4w9WgXcQ]/a.strm") shouldBe null
	}

	test("any of Tentacle's marks makes a YouTube video") {
		item(genres = listOf("YouTube")).isTentacleYouTube shouldBe true
		item(genres = listOf(" youtube ")).isTentacleYouTube shouldBe true
		item(tags = listOf("youtube", "yt:veritasium")).isTentacleYouTube shouldBe true
		item(providerIds = mapOf("Youtube" to "dQw4w9WgXcQ")).isTentacleYouTube shouldBe true
		item(path = strm).isTentacleYouTube shouldBe true
		item(type = BaseItemKind.VIDEO, genres = listOf("YouTube")).isTentacleYouTube shouldBe true
	}

	test("ordinary movies and other kinds are not") {
		item(genres = listOf("Action", "Drama"), providerIds = mapOf("Tmdb" to "949")).isTentacleYouTube shouldBe false
		item(type = BaseItemKind.SERIES, genres = listOf("YouTube")).isTentacleYouTube shouldBe false
		item(type = BaseItemKind.EPISODE, path = strm).isTentacleYouTube shouldBe false
		item(providerIds = mapOf("youtube" to "not-an-id")).isTentacleYouTube shouldBe false
	}

	test("the channel: the studio first, else the folder") {
		item(genres = listOf("YouTube"), studio = "Veritasium: Science").youTubeChannel shouldBe "Veritasium: Science"
		item(genres = listOf("YouTube"), path = strm).youTubeChannel shouldBe "Veritasium"
		item(genres = listOf("YouTube"), studio = " ", path = strm).youTubeChannel shouldBe "Veritasium"
		item(genres = listOf("YouTube")).youTubeChannel shouldBe null
	}

	test("the subtitle is YouTube · channel, or nothing") {
		item(path = strm).youTubeSubtitle shouldBe "YouTube · Veritasium"
		item(genres = listOf("YouTube")).youTubeSubtitle shouldBe null
		youTubeSubtitle("  ") shouldBe null
	}

	test("search cards use it, other movies keep their year") {
		searchCardSubtitle(item(path = strm)) shouldBe "YouTube · Veritasium"
		searchCardSubtitle(item(genres = listOf("YouTube"))) shouldBe ""
		searchCardSubtitle(item(genres = listOf("Action"))) shouldBe "2026"
	}
})
