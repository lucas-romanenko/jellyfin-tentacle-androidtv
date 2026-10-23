package org.jellyfin.androidtv.ui

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.coroutineScope
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.getActivity
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
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Live TV guide's programme popup: Record, Record Series, Cancel recording and Cancel series
 * each run one helper (recordProgram, recordSeries, cancelTimer, cancelSeriesTimer). They acted
 * only on success, so when Jellyfin refused (a 400, a 403 for a profile without Live TV
 * management, a 404 for a timer already gone, a 5xx while it restarts) the popup stayed as it was
 * and nothing was said: a Record that didn't book looked like a press that didn't register (#197).
 *
 * Each test runs the real helper against a Jellyfin stand-in on a local socket; the popup is a
 * mock carrying its context and lifecycle, and every toast is recorded.
 */
class GuidePopupRecordFailureTests : FunSpec({
	val programmeId = UUID.fromString("00000001-0000-4000-8000-0000000000aa")
	val programmeJson = programmeId.toString().replace("-", "")

	/** Jellyfin answering the reads, and every POST or DELETE with [writeStatus]. */
	class FakeJellyfin(val writeStatus: Int, programmeJson: String) : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		val writes = CopyOnWriteArrayList<String>()
		private val defaults = """{"ProgramId":"$programmeJson","Name":"Hockey","PrePaddingSeconds":0,"PostPaddingSeconds":0}"""
		private val programme = """{"Id":"$programmeJson","Type":"Program","Name":"Hockey"}"""

		init {
			thread(isDaemon = true) {
				while (true) {
					val client = try { socket.accept() } catch (_: SocketException) { break }
					client.use {
						val input = client.getInputStream()
						val head = StringBuilder()
						while (!head.endsWith("\r\n\r\n")) {
							val b = input.read()
							if (b < 0) break
							head.append(b.toChar())
						}
						val (method, target) = head.lineSequence().first().split(" ").let { it[0] to it[1] }
						val path = target.substringBefore("?")
						val length = Regex("(?im)^content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
						input.readNBytes(length)

						val (status, json) = when {
							method == "POST" || method == "DELETE" -> { writes += "$method $path"; writeStatus to "" }
							path == "/LiveTv/Timers/Defaults" -> 200 to defaults
							path.startsWith("/LiveTv/Programs/") -> 200 to programme
							else -> 404 to ""
						}
						val body = json.toByteArray()
						client.getOutputStream().apply {
							write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
							write(body)
							flush()
						}
					}
				}
			}
		}

		override fun close() = socket.close()
	}

	/** The popup with its context and lifecycle; its coroutines run on one "main" thread. */
	class Popup(server: FakeJellyfin) {
		private val main = Executors.newSingleThreadExecutor()
		private val job = SupervisorJob()
		val uncaught = CopyOnWriteArrayList<Throwable>()
		val toasts = CopyOnWriteArrayList<String>()
		val done = CopyOnWriteArrayList<String>()
		val popup = mockk<LiveProgramDetailPopup>(relaxed = true)

		init {
			val factory = OkHttpFactory()
			val api = factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
			startKoin { modules(module { single<ApiClient> { api } }) }

			val context = mockk<Context>(relaxed = true)
			every { context.getString(any()) } answers { "res:${firstArg<Int>()}" }
			every { context.getActivity() } returns mockk<Activity>(relaxed = true)
			val scope = mockk<LifecycleCoroutineScope> {
				every { coroutineContext } returns job + main.asCoroutineDispatcher() + CoroutineExceptionHandler { _, e -> uncaught += e }
			}
			val lifecycle = mockk<Lifecycle>(relaxed = true)
			every { lifecycle.coroutineScope } returns scope
			LiveProgramDetailPopup::class.java.getDeclaredField("mContext").apply { isAccessible = true }.set(popup, context)
			LiveProgramDetailPopup::class.java.getDeclaredField("lifecycle").apply { isAccessible = true }.set(popup, lifecycle)

			every { Toast.makeText(any(), any<CharSequence>(), any()) } answers { toasts += secondArg<CharSequence>().toString(); mockk(relaxed = true) }
			every { Toast.makeText(any(), any<Int>(), any()) } answers { toasts += "res:${secondArg<Int>()}"; mockk(relaxed = true) }
		}

		/** Press a button on the main thread and wait until its request has finished. */
		fun press(action: LiveProgramDetailPopup.() -> Unit) {
			main.submit { popup.action() }.get(10, TimeUnit.SECONDS)
			runBlocking { withTimeout(30_000) { while (job.children.any { it.isActive }) job.children.forEach { it.join() } } }
			main.submit {}.get(10, TimeUnit.SECONDS)
			main.shutdown()
		}
	}

	beforeTest {
		mockkStatic("org.jellyfin.androidtv.util.ContextExtensionsKt")
		mockkStatic("androidx.lifecycle.LifecycleKt")
		mockkStatic(Toast::class)
	}
	afterTest {
		stopKoin()
		unmockkAll()
	}

	data class Button(val label: String, val failure: Int, val press: (Popup) -> Unit)

	val buttons = listOf(
		Button("Record", R.string.msg_unable_to_create_recording) { p -> p.press { recordProgram(programmeId) { p.done += "Record" } } },
		Button("Record Series", R.string.msg_unable_to_create_recording) { p -> p.press { recordSeries(programmeId) { p.done += "Record Series" } } },
		Button("Cancel recording", R.string.msg_unable_to_cancel) { p -> p.press { cancelTimer("0123456789abcdef0123456789abcde1") { p.done += "Cancel recording" } } },
		Button("Cancel series", R.string.msg_unable_to_cancel) { p -> p.press { cancelSeriesTimer("0123456789abcdef0123456789abcde2") { p.done += "Cancel series" } } },
	)

	for (button in buttons) {
		test("${button.label}: when Jellyfin refuses, the popup says so") {
			FakeJellyfin(writeStatus = 500, programmeJson).use { server ->
				val p = Popup(server)
				button.press(p)

				p.uncaught.shouldBeEmpty()
				withClue("Jellyfin was never asked to change anything") { server.writes.isNotEmpty() shouldBe true }
				p.done.shouldBeEmpty()
				withClue("nothing was shown after Jellyfin refused \"${button.label}\"") {
					p.toasts shouldBe listOf("res:${button.failure}")
				}
			}
		}

		test("${button.label}: when Jellyfin accepts, the popup goes on as before") {
			FakeJellyfin(writeStatus = 204, programmeJson).use { server ->
				val p = Popup(server)
				button.press(p)

				p.uncaught.shouldBeEmpty()
				p.done shouldBe listOf(button.label)
				p.toasts.shouldBeEmpty()
			}
		}
	}
})
