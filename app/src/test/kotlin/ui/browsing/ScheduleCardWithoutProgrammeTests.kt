package org.jellyfin.androidtv.ui.browsing.v2

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * OK on a Live TV Schedule card (and on the Recordings screen's "Scheduled in next 24 hours" row)
 * for a timer that has no programme in the guide closes the app.
 *
 * Jellyfin sends a timer's ProgramInfo only while its programme is in the guide
 * (LiveTvManager.GetTimers): a timer booked without a programme id (a manual or scheduler-made
 * timer), or one whose programme a guide refresh dropped, comes without it. Both screens then draw
 * a stand-in programme with no channelId, and ItemLauncher hands that null to Kotlin's
 * Destinations.channelDetails(item, channel: UUID, ...): NullPointerException on the main thread.
 */
class ScheduleCardWithoutProgrammeTests : FunSpec({
	/** Jellyfin with one timer, an hour from now, that has no ProgramInfo; no recordings. */
	class FakeServer : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		private val start = LocalDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0)
		private val iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME
		private val timers = """{"Items":[{"Id":"0123456789abcdef0123456789abcdef","Type":"Timer",
			|"ChannelId":"00000001000040008000000000000000","ChannelName":"Sports 1","ProgramId":"",
			|"Name":"Game 4","StartDate":"${start.format(iso)}Z","EndDate":"${start.plusHours(3).format(iso)}Z",
			|"Status":"New","SeriesTimerId":null}],"TotalRecordCount":1,"StartIndex":0}""".trimMargin()
		private val empty = """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""

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
						val path = head.lineSequence().first().split(" ")[1]
						val body = (if (path.startsWith("/LiveTv/Timers")) timers else empty).toByteArray()
						client.getOutputStream().apply {
							write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
							write(body)
							flush()
						}
					}
				}
			}
		}

		override fun close() = socket.close()
	}

	fun api(server: FakeServer): ApiClient {
		val factory = OkHttpFactory()
		return factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
	}

	/** OK on [card], as both screens do it: ItemLauncher.launch(BaseItemDtoBaseRowItem(card), null, context). */
	fun pressOk(card: BaseItemDto) {
		startKoin {
			modules(module {
				single { mockk<NavigationRepository>(relaxed = true) }
				single { mockk<SessionRepository>(relaxed = true) }
				single { mockk<ApiClient>(relaxed = true) }
				single { mockk<MediaManager>(relaxed = true) }
				single { mockk<PlaybackLauncher>(relaxed = true) }
				single { mockk<PlaybackHelper>(relaxed = true) }
			})
		}
		ItemLauncher().launch(BaseItemDtoBaseRowItem(card), null, mockk<Context>(relaxed = true))
	}

	beforeTest {
		mockkStatic("androidx.lifecycle.ViewModelKt")
		every { any<ViewModel>().viewModelScope } returns CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
		// Android stubs a fixed launch may reach: the details destination's Bundle, a toast.
		mockkConstructor(Bundle::class)
		every { anyConstructed<Bundle>().putString(any(), any()) } just runs
		mockkStatic(Toast::class)
		every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
		every { Toast.makeText(any(), any<Int>(), any()) } returns mockk(relaxed = true)
	}
	afterTest {
		stopKoin()
		unmockkAll()
	}

	test("Schedule: OK on a timer without programme info does not close the app") {
		FakeServer().use { server ->
			val viewModel = ScheduleBrowseViewModel(api(server))
			viewModel.initialize(mockk(relaxed = true))
			val state = runBlocking { withTimeout(30_000) { viewModel.uiState.first { !it.isLoading } } }
			val card = state.scheduleGroups.single().items.single()

			shouldNotThrowAny { pressOk(card) }
		}
	}

	test("Recordings, Scheduled in next 24 hours: OK on the same timer does not close the app") {
		FakeServer().use { server ->
			val viewModel = RecordingsBrowseViewModel(api(server))
			viewModel.initialize()
			val state = runBlocking { withTimeout(30_000) { viewModel.uiState.first { !it.isLoading } } }
			val card = state.scheduledNext24h.single()

			shouldNotThrowAny { pressOk(card) }
		}
	}
})
