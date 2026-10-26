package org.jellyfin.androidtv.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Family members on a home server: requests that wait for the admin's approval
 * (/TentacleDiscover/RequestMode, `pending` on an add) and problems in plain words
 * (/TentacleDiscover/FamilyStatus). Pure functions, so the decisions are unit-tested.
 */

private val familyJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

/** GET /TentacleDiscover/RequestMode: "ask" = this user's adds wait for [approver]'s approval. */
@Serializable
data class RequestMode(
	val mode: String = "direct",
	val approver: String? = null,
) {
	val asks: Boolean get() = mode.equals("ask", ignoreCase = true)

	companion object {
		val DIRECT = RequestMode()
	}
}

/** The request mode from the plugin's answer; an older plugin (404), an error or an odd body is "direct". */
fun parseRequestMode(code: Int, body: String?): RequestMode {
	if (code !in 200..299) return RequestMode.DIRECT
	return runCatching { familyJson.decodeFromString<RequestMode>(body.orEmpty()) }.getOrNull() ?: RequestMode.DIRECT
}

@Serializable
data class FamilyStatusMessage(
	/** "livetv", "requests" or "library". */
	val area: String = "",
	val text: String = "",
	/**
	 * Live TV only, from newer servers (null on older ones): down, blocked, account, offline,
	 * other, channels (some channels only) or back (it works again, shown for 30 minutes).
	 */
	val kind: String? = null,
	/** The provider's name, e.g. "IPTV Bears". */
	val provider: String? = null,
	/** When it started and ended (ISO 8601 with offset), as the server sent them. */
	val since: String? = null,
	val until: String? = null,
	/** A short headline; [text] is the full sentence. */
	val title: String? = null,
)

/** GET /TentacleDiscover/FamilyStatus: what is broken right now, in plain words, per area. */
@Serializable
data class FamilyStatus(
	val messages: List<FamilyStatusMessage> = emptyList(),
)

fun parseFamilyStatus(code: Int, body: String?): FamilyStatus? {
	if (code !in 200..299) return null
	return runCatching { familyJson.decodeFromString<FamilyStatus>(body.orEmpty()) }.getOrNull()
}

/** The server's plain-words message for an [area], if it has one. */
fun familyStatusText(status: FamilyStatus?, area: String): String? =
	status?.messages
		?.firstOrNull { it.area.equals(area, ignoreCase = true) && it.text.isNotBlank() }
		?.text?.trim()

/**
 * What Live TV shows: the server's Live TV message when it has one (it says what is wrong and
 * that the admin knows), else the app's own [fallback] (null = say nothing).
 */
fun liveTvNotice(status: FamilyStatus?, fallback: String?): String? =
	familyStatusText(status, "livetv") ?: fallback

/** How the guide's Live TV line looks: a warning, good news, or a plain note. */
enum class GuideBannerTone { WARNING, BACK, NEUTRAL }

/** The line under the guide's header: the server's words and how it looks. */
data class GuideBanner(val text: String, val tone: GuideBannerTone)

/**
 * The guide's Live TV line: the first Live TV message with words (the server puts the most
 * serious first); null when there is none. "back" is good news, "channels" (a few channels
 * only) a plain note; anything else, also an older server's message without a kind, is a warning.
 */
fun liveTvBanner(status: FamilyStatus?): GuideBanner? {
	val message = status?.messages
		?.firstOrNull { it.area.equals("livetv", ignoreCase = true) && it.text.isNotBlank() }
		?: return null
	val tone = when (message.kind?.trim()?.lowercase()) {
		"back" -> GuideBannerTone.BACK
		"channels" -> GuideBannerTone.NEUTRAL
		else -> GuideBannerTone.WARNING
	}
	return GuideBanner(message.text.trim(), tone)
}

/**
 * The line after a status read: no answer ([status] null: an older server, a failed or slow
 * request) keeps what is [shown]; an answer replaces it (no Live TV message = no line).
 */
fun nextGuideBanner(shown: GuideBanner?, status: FamilyStatus?): GuideBanner? =
	if (status == null) shown else liveTvBanner(status)

/** Shown when a request waits for approval and the server didn't say more. */
const val SENT_FOR_APPROVAL = "Sent for approval"

/** What an add to Radarr/Sonarr came to: the message, and whether it now waits for approval (button done). */
data class AddOutcome(val message: String, val requested: Boolean = false)

/** The Discover add buttons' message for [result]; [arr] is "Radarr" or "Sonarr". */
fun addOutcome(result: AddResult, arr: String): AddOutcome = when {
	result.error != null -> AddOutcome("Error: ${result.error}")
	result.added > 0 -> AddOutcome("Added to $arr!")
	result.alreadyExists > 0 -> AddOutcome("Already in $arr")
	// Before the failure branches: a family member's request went to the admin, not to Radarr/Sonarr
	result.pending > 0 -> AddOutcome(result.detail?.takeIf { it.isNotBlank() } ?: SENT_FOR_APPROVAL, requested = true)
	result.detail != null -> AddOutcome(result.detail)
	result.failed > 0 -> AddOutcome("$arr rejected the request")
	else -> AddOutcome("Failed to add")
}

/** The Discover add button's label: "Request" when this user's adds wait for approval. */
fun addButtonLabel(arr: String, mode: RequestMode, adding: Boolean, requested: Boolean): String = when {
	adding -> if (mode.asks) "Sending..." else "Adding..."
	requested -> "Requested"
	mode.asks -> "Request"
	else -> "Add to $arr"
}

/** How long the same notice stays quiet after it was shown (the guide's open and its error, player retries). */
const val NOTICE_REPEAT_MS = 30_000L

/** Show [text] unless the same text was shown less than 30 s ago. */
fun shouldShowNotice(text: String, lastText: String?, lastShownMs: Long, nowMs: Long): Boolean =
	text != lastText || nowMs - lastShownMs !in 0 until NOTICE_REPEAT_MS
