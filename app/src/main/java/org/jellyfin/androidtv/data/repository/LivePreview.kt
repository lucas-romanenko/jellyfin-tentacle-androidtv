package org.jellyfin.androidtv.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The plugin path the guide asks whether a live preview may run (Tentacle's spare connections). */
const val LIVE_PREVIEW_PATH = "/TentacleDiscover/LivePreview"

/**
 * Whether the household has a provider connection to spare for a guide preview: [previews] true when
 * at least one stays free after the preview takes one (recordings about to start count as taken).
 * [limit] 0 = no limit set.
 */
@Serializable
data class LivePreviewAnswer(
	val previews: Boolean = false,
	val limit: Int = 0,
	val active: Int = 0,
	val free: Int = 0,
)

private val livePreviewJson = Json { ignoreUnknownKeys = true }

/** A real answer, or null (no plugin, an older server, an error): the guide then keeps what it shows. */
fun parseLivePreview(code: Int, body: String?): LivePreviewAnswer? {
	if (code !in 200..299 || body.isNullOrBlank()) return null
	return runCatching { livePreviewJson.decodeFromString<LivePreviewAnswer>(body) }.getOrNull()
}
