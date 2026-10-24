package org.jellyfin.androidtv.ui.browsing.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.serializer.toUUID
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Opening a folders library (the DVR's "Recordings" library: one folder per programme title) shows
 * a spinner for 25-29 s on a big server, while the same page without user data takes 4 ms (#204).
 *
 * Jellyfin 10.11 fills a plain Folder's user data (Folder.FillUserDataDtoValues: the unplayed
 * count) with a recursive query over the whole items table, 1-1.5 s a folder, empty folders too,
 * one after the other, before it sends the page. The grid waited for the whole page.
 *
 * The fake server below holds every answer that carries a folder's user data until the test
 * releases [FakeJellyfin.folderCounts]; everything else answers at once.
 */
class FoldersLibraryLoadTests : FunSpec({
	data class Entry(val id: String, val name: String, val folder: Boolean, val userData: String)

	fun ud(id: String, played: Boolean, unplayed: Int? = null) =
		"""{"PlaybackPositionTicks":0,"PlayCount":${if (played) 1 else 0},"IsFavorite":false,"Played":$played,""" +
			(if (unplayed != null) """"UnplayedItemCount":$unplayed,""" else "") +
			""""Key":"$id","ItemId":"$id"}"""

	// Title folders (two or more recordings) and single recordings, as the DVR lays them out.
	val entries = listOf(
		Entry("00000000000000000000000000000001", "NHL Hockey", true, ud("00000000000000000000000000000001", false, 7)),
		Entry("00000000000000000000000000000002", "Basketball", true, ud("00000000000000000000000000000002", false, 11)),
		Entry("00000000000000000000000000000003", "Cup Final", true, ud("00000000000000000000000000000003", true, 0)),
		Entry("00000000000000000000000000000004", "Watched game", false, ud("00000000000000000000000000000004", true)),
		Entry("00000000000000000000000000000005", "New game", false, ud("00000000000000000000000000000005", false)),
	)

	class FakeJellyfin(private val entries: List<Entry>) : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val baseUrl = "http://127.0.0.1:${socket.localPort}"
		/** Counted down: Jellyfin has finished counting the folders' unplayed items. */
		val folderCounts = CountDownLatch(1)
		val itemRequests = CopyOnWriteArrayList<String>()

		private fun json(e: Entry, withUserData: Boolean) =
			"""{"Id":"${e.id}","Name":"${e.name}","Type":"${if (e.folder) "Folder" else "Recording"}","IsFolder":${e.folder},""" +
				""""ServerId":"s"${if (withUserData) ""","UserData":${e.userData}""" else ""}}"""

		private fun answer(target: String): String {
			val path = target.substringBefore('?')
			if (path != "/Items") return """{"Items":[],"TotalRecordCount":0,"StartIndex":0}"""
			itemRequests += target
			// Repeated (ids=a&ids=b) or comma-separated (ids=a,b): Jellyfin takes both.
			val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.groupBy(
				{ URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() },
				{ URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") },
			).mapValues { (_, v) -> v.joinToString(",") }
			val ids = query["ids"]?.split(',')?.map { it.replace("-", "").lowercase() }?.toSet()
			val chosen = if (ids != null) entries.filter { it.id in ids } else entries
			val withUserData = query["enableuserdata"]?.lowercase() != "false"
			if (withUserData && chosen.any { it.folder }) folderCounts.await(60, TimeUnit.SECONDS)
			return """{"Items":[${chosen.joinToString(",") { json(it, withUserData) }}],"TotalRecordCount":${chosen.size},"StartIndex":0}"""
		}

		init {
			thread(isDaemon = true) {
				while (true) {
					val client = try { socket.accept() } catch (_: SocketException) { break }
					thread(isDaemon = true) {
						client.use {
							val input = client.getInputStream()
							val head = StringBuilder()
							while (!head.endsWith("\r\n\r\n")) {
								val b = input.read()
								if (b < 0) break
								head.append(b.toChar())
							}
							val body = answer(head.lineSequence().first().split(" ")[1]).toByteArray()
							try {
								client.getOutputStream().apply {
									write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
									write(body)
									flush()
								}
							} catch (_: SocketException) {
							}
						}
					}
				}
			}
		}

		override fun close() {
			folderCounts.countDown()
			socket.close()
		}
	}

	fun api(server: FakeJellyfin): ApiClient {
		val factory = OkHttpFactory()
		return factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
	}

	/** The library tile as Home / My Media hands it to the grid. */
	fun library(collectionType: CollectionType?) = Json.encodeToString(
		BaseItemDto.serializer(),
		BaseItemDto(
			id = "000000000000000000000000000000aa".toUUID(), type = BaseItemKind.COLLECTION_FOLDER,
			name = "Recordings", isFolder = true, collectionType = collectionType,
		),
	)

	fun viewModel(server: FakeJellyfin) =
		LibraryBrowseViewModel(api(server), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

	beforeTest {
		mockkStatic("androidx.lifecycle.ViewModelKt")
		every { any<ViewModel>().viewModelScope } returns CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
	}
	afterTest { unmockkAll() }

	test("a folders library shows its page while Jellyfin is still counting the folders' unplayed items") {
		FakeJellyfin(entries).use { server ->
			val vm = viewModel(server)
			vm.initialize(library(null), null, null)
			val state = runBlocking { withTimeout(10_000) { vm.uiState.first { !it.isLoading && it.items.isNotEmpty() } } }

			state.items shouldHaveSize 5
		}
	}

	test("the recordings' watched marks arrive while the folders are still being counted") {
		FakeJellyfin(entries).use { server ->
			val vm = viewModel(server)
			vm.initialize(library(null), null, null)
			runBlocking {
				withTimeout(10_000) {
					vm.uiState.first { s -> s.items.any { it.name == "Watched game" && it.userData?.played == true } }
				}
			}
		}
	}

	test("a folder's unplayed count arrives once Jellyfin has counted it") {
		FakeJellyfin(entries).use { server ->
			val vm = viewModel(server)
			vm.initialize(library(null), null, null)
			server.folderCounts.countDown()
			val state = runBlocking {
				withTimeout(10_000) {
					vm.uiState.first { s -> s.items.any { it.name == "NHL Hockey" && it.userData?.unplayedItemCount == 7 } }
				}
			}

			state.items.single { it.name == "Cup Final" }.userData?.played shouldBe true
		}
	}

	test("a movies library keeps its single request with user data") {
		FakeJellyfin(entries).use { server ->
			server.folderCounts.countDown()
			val vm = viewModel(server)
			vm.initialize(library(CollectionType.MOVIES), null, null)
			val state = runBlocking { withTimeout(10_000) { vm.uiState.first { !it.isLoading && it.items.isNotEmpty() } } }

			state.items.single { it.name == "Watched game" }.userData?.played shouldBe true
			server.itemRequests shouldHaveSize 1
			server.itemRequests.single().contains("enableUserData=false", ignoreCase = true) shouldBe false
		}
	}
})
