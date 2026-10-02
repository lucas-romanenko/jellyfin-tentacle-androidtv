package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A guide filter asks for the programmes of every channel. The channel ids go in the request URL,
 * and Jellyfin's web server (Kestrel) answers 414 to a request line over 8 KB, so on a large lineup
 * the filtered guide was empty.
 */
class GuideProgramsRequestTests : FunSpec({
	val start: LocalDateTime = LocalDateTime.of(2026, 10, 2, 12, 0)

	fun channelId(n: Int) = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))

	/** Two programmes per channel, at 12:00 and 13:00. */
	fun programmes(channel: UUID) = (0..1).map { slot ->
		BaseItemDto(
			id = UUID.nameUUIDFromBytes("$channel/$slot".toByteArray()),
			type = BaseItemKind.PROGRAM,
			channelId = channel,
			startDate = start.plusHours(slot.toLong()),
			endDate = start.plusHours(slot + 1L),
		)
	}

	/**
	 * A Jellyfin stand-in on a real socket: refuses a request line over 8 KB with 414 as Kestrel does,
	 * otherwise answers the programmes of the channels named, sorted by start date.
	 * Ids that are not UUIDs are dropped, and no id means every channel.
	 */
	class FakeServer(val lineup: List<UUID>, val programmes: (UUID) -> List<BaseItemDto>) : AutoCloseable {
		val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
		val requestLines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
		@Volatile var failRequest = -1
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
						val line = head.lineSequence().first()
						val index = synchronized(requestLines) { requestLines += line; requestLines.size - 1 }
						val (status, body) = when {
							line.length > 8192 -> 414 to ""
							index == failRequest -> 500 to ""
							else -> 200 to answer(line.split(" ")[1])
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

		fun answer(target: String): String {
			val asked = channelIdsIn(target).mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
			val channels = asked.ifEmpty { lineup }
			val items = channels.flatMap(programmes).sortedBy { it.startDate }
			return Json.encodeToString(BaseItemDtoQueryResult.serializer(), BaseItemDtoQueryResult(items = items, totalRecordCount = items.size, startIndex = 0))
		}

		override fun close() = socket.close()
	}

	fun api(server: FakeServer): ApiClient {
		val factory = OkHttpFactory()
		return factory.create(server.baseUrl, "token", ClientInfo("test", "1"), DeviceInfo("test", "test"), HttpClientOptions(), factory)
	}

	/** Runs the guide's programme request for [ids] against [server] and returns what the guide gets. */
	fun request(server: FakeServer, ids: Array<UUID>): Collection<BaseItemDto>? {
		val client = api(server)
		startKoin { modules(module { single { client } }) }
		val fragment = mockk<Fragment>(relaxed = true)
		val scope = mockk<LifecycleCoroutineScope> {
			every { coroutineContext } returns SupervisorJob() + Dispatchers.Unconfined
		}
		every { fragment.lifecycleScope } returns scope
		val result = CompletableFuture<Collection<BaseItemDto>?>()
		getPrograms(fragment, ids, start, start.plusHours(4)) { result.complete(it) }
		return result.get(30, TimeUnit.SECONDS)
	}

	beforeTest { mockkStatic("androidx.lifecycle.LifecycleOwnerKt") }
	afterTest {
		unmockkStatic("androidx.lifecycle.LifecycleOwnerKt")
		stopKoin()
	}

	test("a filtered guide on 840 channels gets every channel's programmes, each request under 8 KB") {
		val lineup = (0 until 840).map(::channelId)
		FakeServer(lineup, programmes = ::programmes).use { server ->
			val got = request(server, lineup.toTypedArray()).shouldNotBeNull()

			// Every programme once, nothing missing or doubled.
			got.map { it.id } shouldContainExactly got.map { it.id }.distinct()
			got.map { it.id }.toSet() shouldBe lineup.flatMap { programmes(it) }.map { it.id }.toSet()
			// Each channel's programmes in start order, and the channels in lineup order.
			for (channel in lineup) got.filter { it.channelId == channel }.map { it.startDate } shouldContainExactly
				programmes(channel).map { it.startDate }
			got.map { it.channelId }.distinct() shouldBe got.map { it.channelId }.distinct().sortedBy(lineup::indexOf)
			got.map { lineup.indexOf(it.channelId) / LiveTvGuideFragment.PAGE_SIZE } shouldBe
				got.map { lineup.indexOf(it.channelId) / LiveTvGuideFragment.PAGE_SIZE }.sorted()

			// The requests together name every channel once, in lineup order.
			server.requestLines.flatMap { channelIdsIn(it.split(" ")[1]) } shouldContainExactly lineup.map { it.toString() }
			for (line in server.requestLines) line.length shouldBeLessThanOrEqual 8192
		}
	}

	test("one guide page (75 channels) is still one request naming exactly its channels") {
		val lineup = (0 until 840).map(::channelId)
		val page = lineup.subList(150, 150 + LiveTvGuideFragment.PAGE_SIZE)
		FakeServer(lineup, programmes = ::programmes).use { server ->
			val got = request(server, page.toTypedArray()).shouldNotBeNull()
			got.map { it.channelId }.toSet() shouldBe page.toSet()
			server.requestLines.size shouldBe 1
			channelIdsIn(server.requestLines.single().split(" ")[1]) shouldContainExactly page.map { it.toString() }
		}
	}

	test("with no channel ids (the ids not filled yet) one request asks for every channel, once") {
		val lineup = (0 until 840).map(::channelId)
		FakeServer(lineup, programmes = ::programmes).use { server ->
			// TvManager is Java: its id array holds nulls until it is filled.
			@Suppress("UNCHECKED_CAST")
			val got = request(server, arrayOfNulls<UUID>(lineup.size) as Array<UUID>).shouldNotBeNull()
			got.size shouldBe lineup.size * 2
			got.map { it.id } shouldContainExactly got.map { it.id }.distinct()
			server.requestLines.size shouldBe 1
			channelIdsIn(server.requestLines.single().split(" ")[1]).shouldBeEmpty()
		}
	}

	test("a failed request still means no guide data, not a partial guide") {
		val lineup = (0 until 840).map(::channelId)
		FakeServer(lineup, programmes = ::programmes).use { server ->
			server.failRequest = 1
			request(server, lineup.toTypedArray()).shouldBeNull()
		}
	}
})

private fun channelIdsIn(target: String): List<String> = target.substringAfter('?', "")
	.split('&')
	.filter { it.startsWith("channelIds=") }
	.map { URLDecoder.decode(it.substringAfter('='), "UTF-8") }
