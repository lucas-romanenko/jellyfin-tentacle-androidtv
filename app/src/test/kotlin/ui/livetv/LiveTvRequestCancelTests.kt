package org.jellyfin.androidtv.ui.livetv

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Leaving the guide or the player while the channel list is still loading cancels the
 * request. It must not reach the callback as "failed" (null): the guide then reads a null
 * channel list and the player calls requireContext() on a detached fragment, and the app
 * closes.
 */
class LiveTvRequestCancelTests : FunSpec({
	test("a loaded value is passed on") {
		Result.success(listOf(1, 2)).getOrNullUnlessCancelled() shouldBe listOf(1, 2)
	}

	test("a failed request gives null, as before") {
		Result.failure<List<Int>>(IOException("no route")).getOrNullUnlessCancelled() shouldBe null
	}

	test("a cancelled request is rethrown, so no callback runs") {
		shouldThrow<CancellationException> {
			Result.failure<List<Int>>(CancellationException("fragment destroyed")).getOrNullUnlessCancelled()
		}
	}
})
