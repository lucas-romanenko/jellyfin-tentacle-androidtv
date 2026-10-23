package org.jellyfin.androidtv.ui.browsing.v2

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.syncplay.SyncPlayManager
import org.jellyfin.androidtv.data.syncplay.SyncPlayState
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.ThemeMusicPlayer
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
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The Music library screen's rows (Last played is always songs; Latest has songs that aren't
 * grouped into an album) open a card with ItemLauncher.launch(BaseItemDtoBaseRowItem(item), null,
 * context): no row adapter. For a song, ItemLauncher builds the play queue by iterating that
 * adapter (`for (Object item : adapter)`), so OK on a song throws NullPointerException on the main
 * thread and the app closes.
 *
 * The test loads the screen's rows with the real MusicBrowseViewModel from a Jellyfin stand-in,
 * presses OK on the Last played song as the screen does, and checks that the song is played.
 */
class MusicSongLaunchTests : FunSpec({
	val song = UUID.fromString("00000001-0000-4000-8000-0000000000a1")

	/** Jellyfin with a music library whose only played song is [song]; no albums, no playlists. */
	class FakeServer(song: UUID) : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		private val played = """{"Items":[{"Id":"$song","Type":"Audio","MediaType":"Audio","Name":"Song 1",
			|"Album":"Album 1","Artists":["Artist 1"],"UserData":{"Played":true,"PlayCount":1,"PlaybackPositionTicks":0,
			|"IsFavorite":false,"Key":"$song","ItemId":"$song"}}],"TotalRecordCount":1,"StartIndex":0}""".trimMargin()
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
						val body = when {
							path.startsWith("/Items/Latest") -> "[]"
							path.startsWith("/Items") && "IsPlayed" in path -> played
							else -> empty
						}.toByteArray()
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

	beforeTest {
		mockkStatic("androidx.lifecycle.ViewModelKt")
		every { any<ViewModel>().viewModelScope } returns CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
	}
	afterTest {
		stopKoin()
		unmockkAll()
	}

	test("Music library, Last played: OK on a song plays it instead of closing the app") {
		FakeServer(song).use { server ->
			val viewModel = MusicBrowseViewModel(api(server), mockk(relaxed = true))
			viewModel.initialize("""{"Id":"00000009-0000-4000-8000-000000000000","Type":"CollectionFolder","Name":"Music","CollectionType":"music"}""", null, null)
			val rows = runBlocking { withTimeout(30_000) { viewModel.uiState.first { !it.isLoading } } }
			val card = rows.lastPlayed.single()

			// The real PlaybackLauncher; what it hands to the audio player is recorded
			val queue = slot<List<BaseItemDto>>()
			val mediaManager = mockk<MediaManager>(relaxed = true) {
				every { playNow(any(), capture(queue), any(), any()) } returns Unit
			}
			startKoin {
				modules(module {
					single { mockk<NavigationRepository>(relaxed = true) }
					single { mockk<SessionRepository>(relaxed = true) }
					single { mockk<ApiClient>(relaxed = true) }
					single { mediaManager }
					single { mockk<PlaybackHelper>(relaxed = true) }
					single { mockk<ThemeMusicPlayer>(relaxed = true) }
					single {
						// Not in a SyncPlay group
						val syncPlay = mockk<SyncPlayManager> { every { state } returns MutableStateFlow(SyncPlayState()) }
						PlaybackLauncher(mediaManager, mockk(relaxed = true), get(), mockk<UserPreferences>(relaxed = true), syncPlay)
					}
				})
			}

			// OK on the card, as MusicBrowseFragment.launchItem does it
			shouldNotThrowAny {
				ItemLauncher().launch(BaseItemDtoBaseRowItem(card), null, mockk<Context>(relaxed = true))
			}
			queue.captured.map { it.id } shouldContain song
		}
	}
})
