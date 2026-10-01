package org.jellyfin.androidtv.ui.home

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** The home's Live TV rows check: a failed request is "unknown", never a crash. */
class LiveTvProbeTests : FunSpec({
	test("an answer is passed on") {
		runBlocking { liveTvProbe { true } } shouldBe true
		runBlocking { liveTvProbe { false } } shouldBe false
	}

	test("a failed request is unknown (null)") {
		runBlocking { liveTvProbe { throw IOException("timeout") } } shouldBe null
		runBlocking { liveTvProbe { throw IllegalStateException("bad answer") } } shouldBe null
	}

	test("cancellation is not swallowed") {
		shouldThrow<CancellationException> { runBlocking { liveTvProbe { throw CancellationException("left home") } } }
	}

	test("in the home build's shape, a failed check reaches no uncaught-exception handler") {
		// HomeRowsFragment: lifecycleScope.launch { async { check }; ...; launch { await } }.
		// lifecycleScope has no handler, so anything recorded here would crash the app.
		val uncaught = mutableListOf<Throwable>()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
		var stored: Boolean? = true
		val build = scope.launch {
			val deferred = async { liveTvProbe { delay(20); throw IOException("timeout") } }
			launch { stored = deferred.await() ?: return@launch }
		}
		runBlocking { build.join() }
		uncaught.shouldBeEmpty()
		stored shouldBe true // unknown keeps the last known value
	}
})
