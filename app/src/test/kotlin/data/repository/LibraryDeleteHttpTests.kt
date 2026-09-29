package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.UserDto
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

/** deleteLibraryItem against a local server answering like the plugin (a8/03). */
class LibraryDeleteHttpTests : FunSpec({
	fun serve(status: String, body: String): ServerSocket {
		val sock = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
		thread(isDaemon = true) {
			while (!sock.isClosed) {
				val c = try { sock.accept() } catch (e: Exception) { break }
				c.use {
					val r = it.getInputStream().bufferedReader()
					while (true) { val l = r.readLine() ?: break; if (l.isEmpty()) break }
					val bytes = body.toByteArray()
					it.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
					it.getOutputStream().flush()
				}
			}
		}
		return sock
	}
	fun repo(port: Int): TentacleRepository {
		val api = mockk<ApiClient>(relaxed = true)
		every { api.baseUrl } returns "http://127.0.0.1:$port"
		every { api.accessToken } returns "test-token"
		val user = mockk<UserDto>(relaxed = true); every { user.id } returns UUID.randomUUID()
		val users = mockk<UserRepository>(); every { users.currentUser } returns MutableStateFlow(user)
		return TentacleRepository(mockk(relaxed = true), api, users, OkHttpClient())
	}

	test("a failed Radarr delete comes back as Tentacle's reason") {
		val s = serve("502 Bad Gateway", """{"detail":"Failed to delete 'Heat' from Radarr — files may still exist"}""")
		runBlocking { repo(s.localPort).deleteLibraryItem("movie", 949) } shouldBe
			DeleteOutcome.Refused("Failed to delete 'Heat' from Radarr — files may still exist")
		s.close()
	}

	test("nothing listening: unreachable") {
		val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")); val port = s.localPort; s.close()
		runBlocking { repo(port).deleteLibraryItem("movie", 949) } shouldBe DeleteOutcome.Unreachable
	}
})
