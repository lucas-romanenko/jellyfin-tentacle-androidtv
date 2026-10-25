package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * The Favourites screen's rows (Lucas, 24 Oct): Films, Shows and Channels only; recordings and
 * YouTube videos (Movies to Jellyfin) aren't films; empty rows left out.
 */
class FavoritesLayoutTests : FunSpec({
	fun item(name: String, type: BaseItemKind, path: String? = null, genres: List<String>? = null, tags: List<String>? = null) = BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		name = name,
		path = path,
		genres = genres,
		tags = tags,
	)

	val film = item("Jay Kelly", BaseItemKind.MOVIE, path = "/media/movies/Jay Kelly (2025)/Jay Kelly (2025).mkv")
	val show = item("Kate Plus 8", BaseItemKind.SERIES)
	val episode = item("Treehouse & Chicks", BaseItemKind.EPISODE)
	val recording = item("Live: NHL Hockey", BaseItemKind.MOVIE, path = "/config/data/livetv/recordings/Live  NHL Hockey/Live  NHL Hockey 2026_10_23_20_00_00.ts")
	val video = item("Some video", BaseItemKind.MOVIE, tags = listOf("youtube", "yt:some-channel"))
	val tsn = item("TSN 2", BaseItemKind.TV_CHANNEL)
	val cbc = item("CBC", BaseItemKind.TV_CHANNEL)

	test("three rows: films, shows, then channels in the guide's order") {
		val rows = favoriteRows(items = listOf(show, film), channels = listOf(tsn, cbc))
		rows.map { it.kind } shouldContainExactly listOf(FavoriteRowKind.MOVIES, FavoriteRowKind.SHOWS, FavoriteRowKind.CHANNELS)
		rows[0].items shouldContainExactly listOf(film)
		rows[1].items shouldContainExactly listOf(show)
		rows[2].items shouldContainExactly listOf(tsn, cbc)
	}

	test("recordings, YouTube videos and other kinds aren't films") {
		favoriteRows(listOf(recording, video, episode), emptyList()) shouldBe emptyList()
	}

	test("a film TMDB tagged \"youtube\" stays a film") {
		val boBurnham = item("Bo Burnham: Inside", BaseItemKind.MOVIE, tags = listOf("youtube", "comedy"))
		favoriteRows(listOf(boBurnham), emptyList()).single().items shouldContainExactly listOf(boBurnham)
	}

	test("an empty row is left out; a repeated item is shown once") {
		favoriteRows(emptyList(), listOf(tsn)).map { it.kind } shouldContainExactly listOf(FavoriteRowKind.CHANNELS)
		favoriteRows(listOf(film, film), emptyList()).single().items shouldContainExactly listOf(film)
	}

	test("only a favourite added or removed changes the screen") {
		val shown = setOf(film.id.toString(), tsn.id.toString())
		favoritesChanged(shown, listOf(film.id.toString() to true)) shouldBe false
		favoritesChanged(shown, listOf(show.id.toString() to false)) shouldBe false
		favoritesChanged(shown, listOf(tsn.id.toString() to false)) shouldBe true
		favoritesChanged(shown, listOf(cbc.id.toString() to true)) shouldBe true
	}

	test("focus returns to the last card, or its neighbour when it went, else the first card") {
		val rows = favoriteRows(listOf(film, show), listOf(tsn, cbc))
		favoriteFocusTarget(rows, cbc.id.toString(), FavoriteRowKind.CHANNELS, 1) shouldBe cbc.id.toString()
		// CBC unstarred while focused: TSN, now the row's last card
		val after = favoriteRows(listOf(film, show), listOf(tsn))
		favoriteFocusTarget(after, cbc.id.toString(), FavoriteRowKind.CHANNELS, 1) shouldBe tsn.id.toString()
		// Its whole row went: the first card
		val noChannels = favoriteRows(listOf(film, show), emptyList())
		favoriteFocusTarget(noChannels, tsn.id.toString(), FavoriteRowKind.CHANNELS, 0) shouldBe film.id.toString()
		favoriteFocusTarget(rows, null, null, 0) shouldBe film.id.toString()
		favoriteFocusTarget(emptyList(), null, null, 0).shouldBeNull()
	}
})
