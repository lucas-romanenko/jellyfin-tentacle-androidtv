package org.jellyfin.androidtv.data.repository

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** One Radarr/Sonarr add per title at a time (a8/05). */
class AddGateTests : FunSpec({
	test("a second add of the same title while the first runs is refused, not sent") {
		val gate = AddGate()
		val key = AddGate.key("http://jf", "u1", "radarr", 603)
		var sent = 0
		gate.withKey(key, busy = { "busy" }) {
			sent++
			gate.withKey(key, busy = { "busy" }) { sent++; "sent" } shouldBe "busy"
			gate.inFlight.value shouldBe setOf(key)
			"sent"
		} shouldBe "sent"
		sent shouldBe 1
		gate.inFlight.value.shouldBeEmpty()
	}

	test("a failed add frees the title") {
		val gate = AddGate()
		runCatching { gate.withKey("k", busy = { Unit }) { error("Radarr down") } }
		gate.inFlight.value.shouldBeEmpty()
		gate.tryAcquire("k") shouldBe true
	}

	test("another user, another server or another title is a separate add") {
		val keys = setOf(
			AddGate.key("http://jf", "u1", "radarr", 603),
			AddGate.key("http://jf", "u2", "radarr", 603),
			AddGate.key("http://other", "u1", "radarr", 603),
			AddGate.key("http://jf", "u1", "radarr", 604),
			AddGate.key("http://jf", "u1", "sonarr", 603),
			AddGate.key("http://jf", "u1", "sonarr", 0, 81189),
		)
		keys.size shouldBe 6
		AddGate.key("http://jf", "u1", "sonarr", 0, 81189) shouldNotBe AddGate.key("http://jf", "u1", "sonarr", 0, 81190)
	}

	test("random presses, failures and closed dialogs: never two adds of one title at once, and nothing stays held") {
		repeat(1000) { seed ->
			val random = Random(seed)
			val gate = AddGate()
			val running = ConcurrentHashMap<String, AtomicInteger>()
			var maxRunning = 0
			runBlocking(Dispatchers.Default) {
				val jobs = mutableListOf<Job>()
				repeat(random.nextInt(2, 12)) {
					val key = "k${random.nextInt(3)}"
					val work = random.nextLong(1, 20)
					val fails = random.nextInt(4) == 0
					val job = launch {
						runCatching {
							gate.withKey(key, busy = { Unit }) {
								val now = running.getOrPut(key) { AtomicInteger() }.incrementAndGet()
								synchronized(running) { maxRunning = maxOf(maxRunning, now) }
								try {
									delay(work)
									if (fails) error("add failed")
								} finally {
									running.getValue(key).decrementAndGet()
								}
							}
						}
					}
					jobs += job
					if (random.nextInt(3) == 0) launch { delay(random.nextLong(0, 10)); job.cancel() } // dialog closed
					delay(random.nextLong(0, 5))
				}
				jobs.joinAll()
			}
			withClue("seed $seed") {
				maxRunning shouldBeLessThanOrEqual 1
				gate.inFlight.value.shouldBeEmpty()
			}
		}
	}
})
