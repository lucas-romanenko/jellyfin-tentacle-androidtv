package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

/** A profile's queued download toasts and cached Tentacle state stay with that profile (#81). */
class TentacleUserSwitchTests : FunSpec({
	val notification = """{"notifications_enabled":true,"notifications":[{"id":7,"tmdb_id":1,"media_type":"movie","title":"A title","message":"Downloaded"}]}"""

	fun user(id: String): UserDto = mockk(relaxed = true) { every { this@mockk.id } returns UUID.fromString(id) }
	val userA = user("11111111-1111-4111-8111-111111111111")
	val userB = user("22222222-2222-4222-8222-222222222222")

	class Harness(var answer: () -> Pair<Int, String>) {
		val currentUser = MutableStateFlow<UserDto?>(null)
		val repo = TentacleRepository(
			context = mockk(relaxed = true),
			api = mockk<ApiClient> {
				every { baseUrl } returns "http://jellyfin.test"
				every { accessToken } returns "token"
			},
			userRepository = mockk<UserRepository> { every { currentUser } returns this@Harness.currentUser },
			httpClient = OkHttpClient.Builder().addInterceptor { chain ->
				val (code, body) = answer()
				Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
					.body(body.toResponseBody("application/json".toMediaType())).build()
			}.build(),
		)
	}

	test("switching profiles drops the previous profile's queued toasts") {
		val h = Harness { 200 to notification }
		h.currentUser.value = userA
		runBlocking { h.repo.pollNotifications() }
		h.repo.pendingNotifications.value.size shouldBe 1

		// Navbar avatar: the session is destroyed, then the next profile signs in.
		h.currentUser.value = null
		h.currentUser.value = userB
		h.repo.pendingNotifications.value.shouldBeEmpty()
	}

	test("a poll that was still running for the previous profile adds nothing after the switch") {
		val h = Harness { 200 to notification }
		h.currentUser.value = userA
		h.answer = {
			h.currentUser.value = userB // the switch lands while A's request is in flight
			200 to notification
		}
		runBlocking { h.repo.pollNotifications() }
		h.repo.pendingNotifications.value.shouldBeEmpty()
	}

	test("the next profile's own notification with the same id is still shown") {
		val h = Harness { 200 to notification }
		h.currentUser.value = userA
		runBlocking { h.repo.pollNotifications() }
		h.currentUser.value = userB
		h.answer = { 200 to notification.replace("A title", "B title") }
		runBlocking { h.repo.pollNotifications() }
		h.repo.pendingNotifications.value.map { it.title } shouldBe listOf("B title")
	}

	test("the same profile signing in again keeps its queue") {
		val h = Harness { 200 to notification }
		h.currentUser.value = userA
		runBlocking { h.repo.pollNotifications() }
		h.currentUser.value = null
		h.currentUser.value = userA
		h.repo.pendingNotifications.value.size shouldBe 1
	}

	test("a cached 'plugin not installed' is asked again for the next profile") {
		val h = Harness { 404 to "" }
		h.currentUser.value = userA
		runBlocking { h.repo.checkAvailable() } shouldBe false
		h.answer = { 200 to "[]" }
		runBlocking { h.repo.checkAvailable() } shouldBe false // cached for this profile
		h.currentUser.value = userB
		runBlocking { h.repo.checkAvailable() } shouldBe true
	}
})
