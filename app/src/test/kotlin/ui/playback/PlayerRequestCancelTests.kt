package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The player's Record / Cancel recording / channel switch requests (#124 follow-up): leaving
 * the player while one is in flight must not run its failure branch, which touches the
 * destroyed fragment (requireContext(), closePlayer()) and closes the app.
 */
class PlayerRequestCancelTests : FunSpec({
	// Runs a request shaped like recordProgram / cancelTimer / playChannel: runCatching around
	// withContext(IO), then fold(onFailure = { touch the fragment }). The player closes (the
	// scope is cancelled) while the request is still waiting.
	fun leaveWhileRequestRuns(rethrow: Boolean, failWith: Throwable? = null): Pair<List<Throwable>, Int> {
		val uncaught = CopyOnWriteArrayList<Throwable>()
		var failureBranchRuns = 0
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
		val started = CompletableDeferred<Unit>()
		val job = scope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					started.complete(Unit)
					if (failWith != null) throw failWith
					awaitCancellation()
				}
			}.fold(
				onSuccess = {},
				onFailure = {
					if (rethrow) it.rethrowIfCancelled()
					failureBranchRuns++
					// requireContext() on a destroyed fragment
					throw IllegalStateException("Fragment not attached to a context.")
				},
			)
		}
		kotlinx.coroutines.runBlocking {
			started.await()
			if (failWith == null) job.cancel()
			job.join()
		}
		return uncaught to failureBranchRuns
	}

	test("without the rethrow, leaving mid-request runs the failure branch and crashes (the bug)") {
		val (uncaught, runs) = leaveWhileRequestRuns(rethrow = false)
		runs shouldBe 1
		uncaught.size shouldBe 1
		uncaught[0].shouldBeInstanceOf<IllegalStateException>()
	}

	test("with the rethrow, leaving mid-request runs nothing and nothing reaches the crash handler") {
		val (uncaught, runs) = leaveWhileRequestRuns(rethrow = true)
		runs shouldBe 0
		uncaught.size shouldBe 0
	}

	test("a real failure still reaches the failure branch (the toast), as before") {
		val (_, runs) = leaveWhileRequestRuns(rethrow = true, failWith = IOException("400"))
		runs shouldBe 1
	}
})
