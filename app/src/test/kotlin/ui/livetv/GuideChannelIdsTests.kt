package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.LocalDateTime
import java.util.UUID

/** The guide opens around the last watched channel and asks only for the channels it shows (#80). */
class GuideChannelIdsTests : FunSpec({
	val helpers = "org.jellyfin.androidtv.ui.livetv.TvManagerHelperKt"
	val fragment = mockk<Fragment>(relaxed = true)

	fun channels(n: Int) = (0 until n).map {
		BaseItemDto(id = UUID.fromString("%08d-0000-4000-8000-000000000000".format(it)), type = BaseItemKind.TV_CHANNEL)
	}

	/** Loads [all] as the guide does, with [last] stored as the last watched channel; returns the start index. */
	fun load(all: List<BaseItemDto>, last: UUID?): Int {
		val prefs = mockk<SystemPreferences> {
			every { this@mockk[SystemPreferences.liveTvLastChannel] } returns (last?.toString() ?: "")
		}
		startKoin { modules(module { single { prefs } }) }
		every { loadLiveTvChannels(any(), any()) } answers { secondArg<(Collection<BaseItemDto>?) -> Unit>()(all) }
		var ndx = -1
		TvManager.loadAllChannels(fragment) { ndx = it; null }
		return ndx
	}

	/** The channel ids the guide sends with its programme request for channels [start]..[end]. */
	fun requestedIds(start: Int, end: Int): List<UUID?> {
		var sent: List<UUID?> = emptyList()
		every { getPrograms(any(), any(), any(), any(), any()) } answers { sent = secondArg<Array<UUID?>>().toList() }
		val now = LocalDateTime.now()
		TvManager.getProgramsAsync(fragment, start, end, now, now.plusHours(3), object : EmptyResponse(mockk(relaxed = true)) {
			override fun onResponse() = Unit
		})
		return sent
	}

	beforeTest { mockkStatic(helpers) }
	afterTest {
		unmockkStatic(helpers)
		stopKoin()
	}

	test("before any channel was watched, the programme request names the page's channels") {
		val all = channels(5)
		load(all, last = null) shouldBe 0
		// A null id is dropped by the server, which then returns every channel's programmes.
		requestedIds(0, 4) shouldContainExactly all.map { it.id }
	}

	test("the last watched channel is found, so the guide opens on the page holding it") {
		val all = channels(200)
		val ndx = load(all, last = all[150].id)
		// LiveTvGuideFragment.load() starts at ndx - PAGE_SIZE / 2 when ndx >= PAGE_SIZE.
		ndx shouldBe 151
		val start = ndx - LiveTvGuideFragment.PAGE_SIZE / 2
		(150 in start until start + LiveTvGuideFragment.PAGE_SIZE) shouldBe true
		requestedIds(start, start + LiveTvGuideFragment.PAGE_SIZE - 1) shouldContainExactly
			all.subList(start, start + LiveTvGuideFragment.PAGE_SIZE).map { it.id }
	}

	test("a last channel that is no longer in the lineup starts at the beginning") {
		load(channels(200), last = UUID.fromString("99999999-0000-4000-8000-000000000000")) shouldBe 0
	}
})
