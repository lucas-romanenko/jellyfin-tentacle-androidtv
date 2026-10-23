package org.jellyfin.androidtv.util.sdk

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Tuning from the Live TV guide (OK on an airing programme, MEDIA_PLAY, the programme popup's Tune)
 * first asks the server for the channel item: SdkPlaybackHelper.retrieveAndPlay. That request runs
 * on the activity's lifecycleScope with no try and no CoroutineExceptionHandler, so when it fails
 * (Jellyfin restarting after a plugin update, a timeout, a channel id the server no longer has)
 * the exception reaches the main thread's uncaught handler and the app closes.
 *
 * The scope below stands in for the activity's lifecycleScope (SupervisorJob, no handler of its
 * own); its handler records what would have reached the thread's uncaught handler instead.
 */
class RetrieveAndPlayFailureTests : FunSpec({
	val channel = UUID.fromString("00000001-0000-4000-8000-000000000000")

	/** A Jellyfin stand-in that answers every request with [status] and [body]. */
	class FakeServer(val status: Int, val body: String = "") : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"

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
						val bytes = body.toByteArray()
						client.getOutputStream().apply {
							write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
							write(bytes)
							flush()
						}
					}
				}
			}
		}

		override fun close() = socket.close()
	}

	fun helper(server: FakeServer): SdkPlaybackHelper {
		val factory = OkHttpFactory()
		val api = factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
		val prefs = mockk<UserPreferences> { every { this@mockk[UserPreferences.resumeSubtractDuration] } returns "0" }
		return SdkPlaybackHelper(api, mockk(relaxed = true), prefs, mockk(relaxed = true), mockk(relaxed = true))
	}

	/** Runs [play] with an activity as the guide passes it (requireContext()); returns what escaped. */
	fun uncaughtFrom(play: (FragmentActivity) -> Unit): List<Throwable> {
		val uncaught = CopyOnWriteArrayList<Throwable>()
		val job = SupervisorJob()
		val scope = mockk<LifecycleCoroutineScope> {
			every { coroutineContext } returns job + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught += e }
		}
		val activity = mockk<FragmentActivity>(relaxed = true)
		every { activity.lifecycleScope } returns scope
		play(activity)
		runBlocking { withTimeout(30_000) { job.children.toList().joinAll() } }
		return uncaught
	}

	beforeTest {
		mockkStatic("androidx.lifecycle.LifecycleOwnerKt")
		// A fix may tell the viewer with a toast.
		mockkStatic(Toast::class)
		every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
		every { Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)
	}
	afterTest { unmockkAll() }

	test("guide tune while Jellyfin restarts (503) does not close the app") {
		FakeServer(503).use { server ->
			uncaughtFrom { helper(server).retrieveAndPlay(channel, false, it) }.shouldBeEmpty()
		}
	}

	test("guide tune of a channel the server no longer has (404) does not close the app") {
		FakeServer(404).use { server ->
			uncaughtFrom { helper(server).retrieveAndPlay(channel, false, it) }.shouldBeEmpty()
		}
	}

	test("play by ids when the server returns none of them does not close the app") {
		FakeServer(200, """{"Items":[],"TotalRecordCount":0,"StartIndex":0}""").use { server ->
			uncaughtFrom { helper(server).retrieveAndPlay(listOf(channel), false, null, null, it) }.shouldBeEmpty()
		}
	}
})
