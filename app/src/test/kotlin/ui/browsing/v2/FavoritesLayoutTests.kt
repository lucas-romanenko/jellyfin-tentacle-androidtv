package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * The Favourites screen's rows (Lucas, 24 Oct): Tentacle web's order, the favourite channels the
 * third row, recordings and YouTube told apart from films, empty rows left out.
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
	val video = item("Some video", BaseItemKind.MOVIE, genres = listOf("YouTube"))
	val tsn = item("TSN 2", BaseItemKind.TV_CHANNEL)
	val cbc = item("CBC", BaseItemKind.TV_CHANNEL)
	val ytLive = item("Lofi Girl", BaseItemKind.TV_CHANNEL, tags = listOf("tentacle-youtube-942bf12a"))
	val person = item("Ryan Reynolds", BaseItemKind.PERSON)

	test("rows come in the web's order, channels third, and empty rows are left out") {
		val rows = favoriteRows(
			items = listOf(episode, recording, show, video, film),
			channels = listOf(tsn, cbc, ytLive),
			people = listOf(person),
		)
		rows.map { it.kind } shouldContainExactly listOf(
			FavoriteRowKind.MOVIES,
			FavoriteRowKind.SHOWS,
			FavoriteRowKind.CHANNELS,
			FavoriteRowKind.YOUTUBE,
			FavoriteRowKind.RECORDINGS,
			FavoriteRowKind.EPISODES,
			FavoriteRowKind.PEOPLE,
		)
		rows[0].items shouldContainExactly listOf(film)
		// The guide's order stays; YouTube's live channels go with YouTube, after its videos
		rows[2].items shouldContainExactly listOf(tsn, cbc)
		rows[3].items shouldContainExactly listOf(video, ytLive)
		rows[4].items shouldContainExactly listOf(recording)
	}

	test("with only channels, they are the only row") {
		favoriteRows(emptyList(), listOf(tsn), emptyList()).map { it.kind } shouldContainExactly listOf(FavoriteRowKind.CHANNELS)
		favoriteRows(emptyList(), emptyList(), emptyList()) shouldBe emptyList()
	}

	test("a repeated item is shown once") {
		favoriteRows(listOf(film, film), emptyList(), emptyList()).single().items shouldContainExactly listOf(film)
	}

	test("only a favourite added or removed changes the screen") {
		val shown = setOf(film.id.toString(), tsn.id.toString())
		favoritesChanged(shown, listOf(film.id.toString() to true)) shouldBe false
		favoritesChanged(shown, listOf(show.id.toString() to false)) shouldBe false
		favoritesChanged(shown, listOf(tsn.id.toString() to false)) shouldBe true
		favoritesChanged(shown, listOf(cbc.id.toString() to true)) shouldBe true
	}

	test("focus returns to the last card, or its neighbour when it went, else the first card") {
		val rows = favoriteRows(listOf(film, show), listOf(tsn, cbc), emptyList())
		favoriteFocusTarget(rows, cbc.id.toString(), FavoriteRowKind.CHANNELS, 1) shouldBe cbc.id.toString()
		// CBC unstarred while focused: TSN, now the row's last card
		val after = favoriteRows(listOf(film, show), listOf(tsn), emptyList())
		favoriteFocusTarget(after, cbc.id.toString(), FavoriteRowKind.CHANNELS, 1) shouldBe tsn.id.toString()
		// Its whole row went: the first card
		val noChannels = favoriteRows(listOf(film, show), emptyList(), emptyList())
		favoriteFocusTarget(noChannels, tsn.id.toString(), FavoriteRowKind.CHANNELS, 0) shouldBe film.id.toString()
		favoriteFocusTarget(rows, null, null, 0) shouldBe film.id.toString()
		favoriteFocusTarget(emptyList(), null, null, 0).shouldBeNull()
	}
})
