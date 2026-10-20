package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.data.repository.SectionsAnswer
import org.jellyfin.androidtv.data.repository.TentacleSectionsResponse
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** When a home refresh applies an answer, and that refreshes never overlap (androidtv audit H2, M3). */
class HomeRefreshTests : FunSpec({
	fun on(version: Long) = SectionsAnswer.On(TentacleSectionsResponse(enabled = true, homeVersion = version))

	test("the same version is skipped, a new one applied") {
		decideHomeRefresh(on(5), shownVersion = 5, showingTentacleHome = true, force = false) shouldBe HomeAnswerAction.SKIP
		decideHomeRefresh(on(6), shownVersion = 5, showingTentacleHome = true, force = false) shouldBe HomeAnswerAction.APPLY
	}

	test("a change without a version still refreshes") {
		decideHomeRefresh(on(5), shownVersion = 5, showingTentacleHome = true, force = true) shouldBe HomeAnswerAction.APPLY
	}

	test("an older plugin has no version: refresh as before") {
		decideHomeRefresh(on(0), shownVersion = 0, showingTentacleHome = true, force = false) shouldBe HomeAnswerAction.APPLY
	}

	test("no answer keeps the rows") {
		decideHomeRefresh(SectionsAnswer.Failed, 5, showingTentacleHome = true, force = true) shouldBe HomeAnswerAction.KEEP
	}

	test("the home switched off shows the stock rows, switched back on shows Tentacle's") {
		decideHomeRefresh(SectionsAnswer.Off(9), 5, showingTentacleHome = true, force = false) shouldBe HomeAnswerAction.SWITCH_TO_STOCK
		decideHomeRefresh(SectionsAnswer.Off(9), 9, showingTentacleHome = false, force = false) shouldBe HomeAnswerAction.SKIP
		decideHomeRefresh(on(10), 9, showingTentacleHome = false, force = false) shouldBe HomeAnswerAction.APPLY
	}

	test("an older plugin's off is not trusted (it said so on a failed read)") {
		decideHomeRefresh(SectionsAnswer.Off(0), 0, showingTentacleHome = true, force = true) shouldBe HomeAnswerAction.SKIP
	}

	test("requests merge: anything asked for is done") {
		val a = HomeRefreshRequest(settle = 1.seconds)
		val b = HomeRefreshRequest(stockRows = true, force = true, settle = 3.seconds)
		a.merge(b) shouldBe HomeRefreshRequest(stockRows = true, force = true, settle = 3.seconds)
		b.merge(a) shouldBe a.merge(b)
	}

	test("nothing runs before the first load, then one refresh for everything asked meanwhile") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			val ready = CompletableDeferred<Unit>()
			val ran = Collections.synchronizedList(mutableListOf<HomeRefreshRequest>())
			val refresher = SerialRefresher<HomeRefreshRequest>(scope, ready, HomeRefreshRequest::merge, { it.settle }) { ran += it }
			refresher.request(HomeRefreshRequest())
			refresher.request(HomeRefreshRequest(stockRows = true))
			delay(100.milliseconds)
			ran.size shouldBe 0
			ready.complete(Unit)
			withTimeout(5.seconds) { while (ran.isEmpty()) delay(10.milliseconds) }
			delay(100.milliseconds)
			ran.toList() shouldBe listOf(HomeRefreshRequest(stockRows = true))
		} finally {
			scope.cancel()
		}
	}

	test("one refresh at a time, and the newest request runs last") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			val ready = CompletableDeferred(Unit)
			val running = java.util.concurrent.atomic.AtomicInteger(0)
			var overlapped = false
			val ran = Collections.synchronizedList(mutableListOf<Int>())
			val refresher = SerialRefresher<Int>(scope, ready, { _, newer -> newer }, { 0.seconds }) { n ->
				if (running.incrementAndGet() > 1) overlapped = true
				delay(50.milliseconds)
				ran += n
				running.decrementAndGet()
			}
			for (n in 1..20) {
				refresher.request(n)
				delay(7.milliseconds)
			}
			withTimeout(5.seconds) { while (ran.lastOrNull() != 20) delay(10.milliseconds) }
			overlapped shouldBe false
			ran.toList() shouldBe ran.sorted()
			(ran.size < 20) shouldBe true
		} finally {
			scope.cancel()
		}
	}

	test("a failed refresh doesn't stop the next one") {
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			val ran = Collections.synchronizedList(mutableListOf<Int>())
			val refresher = SerialRefresher<Int>(scope, CompletableDeferred(Unit), { _, b -> b }, { 0.seconds }) { n ->
				if (n == 1) error("server said no")
				ran += n
			}
			refresher.request(1)
			delay(50.milliseconds)
			refresher.request(2)
			withTimeout(5.seconds) { while (ran.isEmpty()) delay(10.milliseconds) }
			ran.toList() shouldBe listOf(2)
		} finally {
			scope.cancel()
		}
	}
})
