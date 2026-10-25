package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Multi-view (Tentacle plugin, /TentacleDiscover/MultiView): 2-4 Live TV channels in one picture
 * that Tentacle makes, one sound track per channel. The server decides what can start (free
 * provider connections, recordings first) and says why not in plain words.
 */
class MultiViewRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	httpClient: OkHttpClient,
) {
	// Starting opens up to four channels and waits for their first seconds (the plugin waits 60 s).
	private val client: OkHttpClient = httpClient.newBuilder()
		.connectTimeout(5, TimeUnit.SECONDS)
		.readTimeout(65, TimeUnit.SECONDS)
		.callTimeout(70, TimeUnit.SECONDS)
		.build()

	/** Can this person start one now, with how many channels; their running one. Null: unknown. */
	suspend fun state(): MultiViewState? = withContext(Dispatchers.IO) {
		try {
			call(Request.Builder().url(url("/TentacleDiscover/MultiView")).get().build()) { code, body ->
				if (code == 200) multiViewJson.decodeFromString<MultiViewState>(body) else null
			}
		} catch (e: Exception) {
			Timber.d(e, "Multi-view state unavailable")
			null
		}
	}

	suspend fun start(itemIds: List<String>): MultiViewAnswer = send("POST", "/TentacleDiscover/MultiView", itemIds)

	suspend fun change(sessionId: String, itemIds: List<String>): MultiViewAnswer =
		send("PUT", "/TentacleDiscover/MultiView/$sessionId", itemIds)

	/** Still here (a channel plays full screen). Null session: it ended on the server. */
	suspend fun keepAlive(sessionId: String): MultiViewAnswer = withContext(Dispatchers.IO) {
		try {
			val request = Request.Builder().url(url("/TentacleDiscover/MultiView/$sessionId/Keepalive"))
				.post(ByteArray(0).toRequestBody(null)).build()
			call(request) { code, body -> answer(code, body) }
		} catch (e: Exception) {
			Timber.d(e, "Multi-view keepalive failed")
			MultiViewAnswer(error = null, unreachable = true)
		}
	}

	/** Gives every connection back. Fire and forget: the server also closes it after 45 s alone. */
	suspend fun close(sessionId: String) = withContext(Dispatchers.IO) {
		try {
			call(Request.Builder().url(url("/TentacleDiscover/MultiView/$sessionId")).delete().build()) { _, _ -> }
		} catch (e: Exception) {
			Timber.d(e, "Multi-view close failed")
		}
	}

	/** The picture's address for the player (anonymous: the session id is the secret). */
	fun streamUrl(sessionId: String): String {
		val baseUrl = api.baseUrl?.trimEnd('/') ?: error("API base URL not set")
		return "$baseUrl/TentacleDiscover/MultiView/$sessionId/Stream"
	}

	private suspend fun send(method: String, path: String, itemIds: List<String>): MultiViewAnswer =
		withContext(Dispatchers.IO) {
			try {
				val body = multiViewJson.encodeToString(MultiViewStartBody(itemIds))
					.toRequestBody("application/json".toMediaType())
				call(Request.Builder().url(url(path)).method(method, body).build()) { code, text -> answer(code, text) }
			} catch (e: Exception) {
				Timber.w(e, "Multi-view $method failed")
				MultiViewAnswer(error = null, unreachable = true)
			}
		}

	private fun <T> call(request: Request, read: (Int, String) -> T): T =
		client.newCall(request).execute().use { response -> read(response.code, response.body?.string().orEmpty()) }

	private fun url(path: String): String {
		val baseUrl = api.baseUrl?.trimEnd('/') ?: error("API base URL not set")
		val userId = userRepository.currentUser.value?.id ?: error("User ID not set")
		val token = api.accessToken ?: error("Access token not set")
		return "$baseUrl$path?userId=$userId&api_key=$token"
	}
}

internal val multiViewJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

/** A session or the server's reason, from a status code and its body. */
internal fun answer(code: Int, body: String): MultiViewAnswer {
	if (code in 200..299) {
		return try {
			MultiViewAnswer(session = multiViewJson.decodeFromString<MultiViewSession>(body))
		} catch (e: Exception) {
			MultiViewAnswer(error = null)
		}
	}
	return MultiViewAnswer(error = detailOf(body), status = code)
}

/** FastAPI's {"detail": "..."} (or the plugin's), else null. */
internal fun detailOf(body: String): String? = try {
	val obj = multiViewJson.parseToJsonElement(body) as? JsonObject
	(obj?.get("detail") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
} catch (e: Exception) {
	null
}

@Serializable
data class MultiViewStartBody(@SerialName("item_ids") val itemIds: List<String>)

/** A start / change / keepalive: the session, or why not ([error] null = no reason given). */
data class MultiViewAnswer(
	val session: MultiViewSession? = null,
	val error: String? = null,
	val status: Int = 0,
	val unreachable: Boolean = false,
)

@Serializable
data class MultiViewState(
	val available: Boolean = false,
	@SerialName("can_start") val canStart: Boolean = false,
	@SerialName("max_tiles") val maxTiles: Int = 0,
	@SerialName("min_tiles") val minTiles: Int = 2,
	val free: Int? = null,
	val message: String = "",
	val session: MultiViewSession? = null,
)

@Serializable
data class MultiViewSession(
	val id: String,
	val width: Int = 1920,
	val height: Int = 1080,
	val encoder: String? = null,
	val tiles: List<MultiViewTile> = emptyList(),
)

@Serializable
data class MultiViewTile(
	val index: Int,
	@SerialName("audio_track") val audioTrack: Int = index,
	@SerialName("item_id") val itemId: String = "",
	val name: String = "",
	@SerialName("has_audio") val hasAudio: Boolean = true,
	val live: Boolean = true,
	val x: Int = 0,
	val y: Int = 0,
	val width: Int = 960,
	val height: Int = 540,
)
