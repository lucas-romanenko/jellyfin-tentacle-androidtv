package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.presentation.isWideForPosterCard
import org.jellyfin.androidtv.ui.search.composable.searchCardSubtitle
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

/**
 * A Live TV recording is a Movie to Jellyfin: its card says "Recording · Fri 23 Oct", never
 * "Movie • 2026" (Lucas, 24 Oct: under "Live: NHL Hockey" in Continue Watching).
 */
class TentacleRecordingTests : FunSpec({
	val hockey = "/config/data/livetv/recordings/Live  NHL Hockey/Live  NHL Hockey 2026_10_23_20_00_00.ts"

	fun item(
		path: String? = hockey,
		type: BaseItemKind = BaseItemKind.MOVIE,
		created: LocalDateTime? = LocalDateTime.of(2026, 10, 23, 23, 55),
	) = BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		name = "Live: NHL Hockey",
		productionYear = 2026,
		path = path,
		dateCreated = created,
	)

	beforeSpec { Locale.setDefault(Locale.UK) }

	test("Jellyfin's recordings folder, or its recording file name elsewhere") {
		isLiveTvRecordingPath(hockey) shouldBe true
		isLiveTvRecordingPath("/config/data/livetv/recordings/Heat (1995)/Heat (1995).ts") shouldBe true
		isLiveTvRecordingPath("""C:\ProgramData\Jellyfin\Server\data\livetv\recordings\Jeopardy!\x.ts""") shouldBe true
		isLiveTvRecordingPath("/mnt/dvr/Jeopardy!/Jeopardy! 2026_10_21_19_30_00.ts") shouldBe true
	}

	test("films, episodes and YouTube videos are not recordings") {
		isLiveTvRecordingPath(null) shouldBe false
		isLiveTvRecordingPath("/data/media/movies/Heat (1995)/Heat (1995).mkv") shouldBe false
		isLiveTvRecordingPath("/data/media/vod/Movies/One Last Shot (2026)/One Last Shot (2026).strm") shouldBe false
		isLiveTvRecordingPath("/data/media/movies/Some Film/Some Film.ts") shouldBe false
		item(type = BaseItemKind.EPISODE).isLiveTvRecording shouldBe false
		item(type = BaseItemKind.SERIES).isLiveTvRecording shouldBe false
	}

	test("the card says Recording and the day it aired, not Movie and the year") {
		val recording = item()
		recording.isLiveTvRecording shouldBe true
		recording.hasTentacleLabel shouldBe true
		recording.tentacleLabel shouldBe "Recording · Fri 23 Oct"
		searchCardSubtitle(recording) shouldBe "Recording · Fri 23 Oct"
	}

	test("the air day comes from the file name, else when the file was made") {
		item(path = "/config/data/livetv/recordings/Heat (1995)/Heat (1995).ts",
			created = LocalDateTime.of(2026, 10, 20, 1, 0)).recordingLabel shouldBe "Recording · Tue 20 Oct"
		item(path = "/config/data/livetv/recordings/Heat (1995)/Heat (1995).ts", created = null)
			.recordingLabel shouldBe "Recording"
		recordingLabel(LocalDate.of(2026, 10, 24), Locale.UK) shouldBe "Recording · Sat 24 Oct"
	}

	test("an ordinary film keeps its year") {
		val film = item(path = "/data/media/movies/Heat (1995)/Heat (1995).mkv")
		film.hasTentacleLabel shouldBe false
		film.tentacleLabel shouldBe null
		searchCardSubtitle(film) shouldBe "2026"
	}

	test("a recording's own 16:9 picture is shown whole on a poster card; its poster fills it") {
		fun primary(aspect: Double) = BaseItemDto(
			id = UUID.randomUUID(), type = BaseItemKind.MOVIE, path = hockey,
			imageTags = mapOf(ImageType.PRIMARY to "p"), primaryImageAspectRatio = aspect,
		).itemImages[ImageType.PRIMARY]!!
		isWideForPosterCard(primary(16.0 / 9.0)) shouldBe true
		isWideForPosterCard(primary(2.0 / 3.0)) shouldBe false
	}
})
