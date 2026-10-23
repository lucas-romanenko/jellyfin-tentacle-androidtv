package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Cancel series on a programme's details page (and on the series timer page) sent the series timer
 * id to DELETE /LiveTv/Timers/{id}. Jellyfin 10.11 answers 404 to that, the failure was dropped and
 * the series kept recording (#195). The helpers run against a Jellyfin stand-in that answers like
 * 10.11: 204 for the timer and series timer it knows on their own routes, 404 for anything else.
 */
class ProgrammeCancelSeriesTests : FunSpec({
	val timerId = "6c6f6e676d61746368000000000000a1"
	val seriesTimerId = "6c6f6e676d61746368000000000000b2"

	class FakeJellyfin : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		val requests = CopyOnWriteArrayList<String>()

		init {
			thread(isDaemon = true) {
				while (true) {
					val client = try { socket.accept() } catch (_: SocketException) { break }
					thread(isDaemon = true) { runCatching { client.use { handle(it) } } }
				}
			}
		}

		private fun handle(client: Socket) {
			val input = client.getInputStream()
			val head = StringBuilder()
			while (!head.endsWith("\r\n\r\n")) {
				val b = input.read()
				if (b < 0) return
				head.append(b.toChar())
			}
			val (method, target) = head.lineSequence().first().split(" ").let { it[0] to it[1] }
			val path = target.substringBefore("?")
			requests += "$method $path"

			val status = when ("$method $path") {
				"DELETE /LiveTv/Timers/$timerId", "DELETE /LiveTv/SeriesTimers/$seriesTimerId" -> 204
				else -> 404
			}
			client.getOutputStream().apply {
				write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
				flush()
			}
		}

		override fun close() = socket.close()
	}

	class Page {
		val job = SupervisorJob()
		val uncaught = CopyOnWriteArrayList<Throwable>()
		val toasts = CopyOnWriteArrayList<Int>()
		var done = false
		val fragment = mockk<FullDetailsFragment>(relaxed = true)

		fun settle() = runBlocking { withTimeout(30_000) { job.children.forEach { it.join() } } }
	}

	fun page(server: FakeJellyfin): Page {
		val factory = OkHttpFactory()
		val api = factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
		startKoin { modules(module { single<ApiClient> { api } }) }

		val page = Page()
		every { page.fragment.requireContext() } returns mockk<Context>(relaxed = true)
		val scope = mockk<LifecycleCoroutineScope> {
			every { coroutineContext } returns page.job + Dispatchers.Default + CoroutineExceptionHandler { _, e -> page.uncaught += e }
		}
		every { (page.fragment as LifecycleOwner).lifecycleScope } returns scope
		every { Toast.makeText(any(), any<Int>(), any()) } answers { page.toasts += arg<Int>(1); mockk(relaxed = true) }
		return page
	}

	beforeTest {
		mockkStatic("androidx.lifecycle.LifecycleOwnerKt")
		mockkStatic(Toast::class)
	}
	afterTest {
		stopKoin()
		unmockkAll()
	}

	test("Cancel series cancels the series timer") {
		FakeJellyfin().use { server ->
			val page = page(server)

			page.fragment.cancelLiveTvSeriesTimer(seriesTimerId) { page.done = true }
			page.settle()

			page.uncaught.shouldBeEmpty()
			withClue("Jellyfin was asked: ${server.requests}") {
				server.requests shouldContainExactly listOf("DELETE /LiveTv/SeriesTimers/$seriesTimerId")
			}
			page.done shouldBe true
		}
	}

	test("a cancel Jellyfin refuses says so instead of closing silently") {
		FakeJellyfin().use { server ->
			val page = page(server)

			page.fragment.cancelLiveTvSeriesTimer("00000000000000000000000000000000") { page.done = true }
			page.settle()

			page.uncaught.shouldBeEmpty()
			page.done shouldBe false
			page.toasts shouldContainExactly listOf(R.string.msg_unable_to_cancel)
		}
	}
})
