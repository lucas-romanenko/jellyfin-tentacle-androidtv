package org.jellyfin.androidtv.ui.itemdetail

import android.os.Handler
import androidx.lifecycle.Lifecycle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.util.UUID

/**
 * Coming back to the (legacy) details page after playback: only a different episode reloads the
 * page; the page's own episode is refreshed in place.
 */
class DetailsAfterPlaybackTests : FunSpec({
	val helpers = "org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragmentHelperKt"
	fun episode(n: Int) = BaseItemDto(id = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n)), type = BaseItemKind.EPISODE)
	fun field(name: String) = FullDetailsFragment::class.java.getDeclaredField(name).apply { isAccessible = true }

	/** Returns to the details page of [shown] after [played] was played. */
	fun resume(shown: BaseItemDto, played: BaseItemDto): Pair<FullDetailsFragment, DataRefreshService> {
		val prefs = mockk<UserPreferences> { every { this@mockk[UserPreferences.clockBehavior] } returns ClockBehavior.NEVER }
		val refresh = DataRefreshService().apply {
			lastPlayback = Instant.now()
			lastPlayedItem = played
		}
		val lifecycle = mockk<Lifecycle> { every { currentState } returns Lifecycle.State.RESUMED }
		val fragment = mockk<FullDetailsFragment>(relaxed = true)
		every { fragment.onResume() } answers { callOriginal() }
		every { fragment.lifecycle } returns lifecycle
		every { fragment["loadItem"](any<UUID>()) } answers { Unit }
		field("userPreferences").set(fragment, lazyOf(prefs))
		field("dataRefreshService").set(fragment, lazyOf(refresh))
		field("mLastUpdated").set(fragment, Instant.now().minusSeconds(60))
		field("mBaseItem").set(fragment, shown)

		fragment.onResume()
		return fragment to refresh
	}

	beforeTest {
		mockkStatic(helpers)
		every { any<FullDetailsFragment>().getItem(any(), any()) } just runs
		// Run the delayed refresh at once.
		mockkConstructor(Handler::class)
		every { anyConstructed<Handler>().postDelayed(any(), any()) } answers { firstArg<Runnable>().run(); true }
	}
	afterTest { unmockkAll() }

	test("the page's own episode is refreshed in place, not reloaded") {
		val shown = episode(1)
		val (fragment, _) = resume(shown, played = episode(1))
		verify(exactly = 0) { fragment["loadItem"](any<UUID>()) }
		verify { fragment.getItem(shown.id, any()) }
	}

	test("another episode played after it (autoplay) reloads the page with that episode") {
		val next = episode(2)
		val (fragment, refresh) = resume(episode(1), played = next)
		verify { fragment["loadItem"](next.id) }
		refresh.lastPlayedItem shouldBe null
	}
})
