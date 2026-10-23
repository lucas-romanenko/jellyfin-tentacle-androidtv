package org.jellyfin.androidtv.ui.notification

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.data.repository.TentacleNotification
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment
import java.util.Collections

/**
 * A notification is dismissed on the server only once its toast was on screen (Sony check,
 * 23 Oct 2026: Home's toast timer ran while Activity covered Home and ate the request toasts).
 */
class NotificationToastQueueTests : FunSpec({
	class Harness(canShow: Boolean) {
		val scope = CoroutineScope(Dispatchers.Default)
		val pending = MutableStateFlow<List<TentacleNotification>>(emptyList())
		val canShow = MutableStateFlow(canShow)
		val seen: MutableList<Int> = Collections.synchronizedList(mutableListOf())
		val queue = NotificationToastQueue(scope, pending, this.canShow, onSeen = { n ->
			seen += n.id
			pending.value = pending.value.filter { it.id != n.id }
		}, showMs = 400, exitMs = 20).also { it.start() }
	}

	fun request(id: Int) = TentacleNotification(id = id, title = "Dune", kind = "request")

	test("a toast that can show is shown, then marked seen") {
		val h = Harness(canShow = true)
		h.pending.value = listOf(request(1))
		delay(100)
		h.queue.shown.value?.id shouldBe 1
		h.seen.shouldBeEmpty()
		delay(400)
		h.queue.shown.value shouldBe null
		h.seen shouldBe listOf(1)
		h.scope.cancel()
	}

	test("one arriving while toasts can't show waits, unseen, and shows in full later") {
		val h = Harness(canShow = false)
		h.pending.value = listOf(request(1))
		delay(600)
		h.queue.shown.value shouldBe null
		h.seen.shouldBeEmpty()
		h.canShow.value = true
		delay(100)
		h.queue.shown.value?.id shouldBe 1
		delay(400)
		h.seen shouldBe listOf(1)
		h.scope.cancel()
	}

	test("hidden part way through, it isn't marked seen and comes back with its full time") {
		val h = Harness(canShow = true)
		h.pending.value = listOf(request(1))
		delay(150)
		h.canShow.value = false
		delay(20)
		h.queue.shown.value shouldBe null
		delay(500)
		h.seen.shouldBeEmpty()
		h.canShow.value = true
		delay(200)
		h.queue.shown.value?.id shouldBe 1
		h.seen.shouldBeEmpty()
		delay(350)
		h.seen shouldBe listOf(1)
		h.scope.cancel()
	}

	test("closed by the user: seen once, and the next one shows") {
		val h = Harness(canShow = true)
		h.pending.value = listOf(request(1), request(2))
		delay(100)
		h.queue.close(request(1))
		delay(150)
		h.seen shouldBe listOf(1)
		h.queue.shown.value?.id shouldBe 2
		delay(500)
		h.seen shouldBe listOf(1, 2)
		h.scope.cancel()
	}

	test("closing one that isn't on screen does nothing") {
		val h = Harness(canShow = false)
		h.pending.value = listOf(request(1))
		h.queue.close(request(1))
		delay(100)
		h.seen.shouldBeEmpty()
		h.scope.cancel()
	}

	test("toasts show on every screen but the players, with the app in front and no screensaver") {
		toastCanShow(resumed = true, screen = null, screensaver = false) shouldBe true
		toastCanShow(resumed = true, screen = HomeFragment::class.java, screensaver = false) shouldBe true
		toastCanShow(resumed = true, screen = CustomPlaybackOverlayFragment::class.java, screensaver = false) shouldBe false
		toastCanShow(resumed = true, screen = VideoPlayerFragment::class.java, screensaver = false) shouldBe false
		toastCanShow(resumed = false, screen = HomeFragment::class.java, screensaver = false) shouldBe false
		toastCanShow(resumed = true, screen = HomeFragment::class.java, screensaver = true) shouldBe false
	}
})
