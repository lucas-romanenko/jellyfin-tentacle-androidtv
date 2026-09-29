@file:JvmName("PlayerErrorPolicy")

package org.jellyfin.androidtv.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A live stream that shows no progress this long, once it has played, is retried (#60). */
const val LIVE_STALL_MS = 20_000L

/** The same before the first frame: opening a tuner and starting its remux can take a while. */
const val LIVE_START_STALL_MS = 30_000L

/**
 * Whether a live TV error can be retried by preparing the same stream again, before asking the
 * server for a new one (#19). Asking again opens another consumer on the tuner, kills the
 * running transcode and can cost an upstream connection. Network errors, a 5xx on a segment and
 * falling behind the live window are worth one in-place retry; a decoder or format error isn't.
 */
fun canReprepareLiveInPlace(errorCode: Int, httpStatus: Int?): Boolean = when (errorCode) {
	PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
	PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
	PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
	PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
	PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus == null || httpStatus >= 500
	else -> false
}

/** The HTTP status behind a player error, if the server answered one. */
@OptIn(UnstableApi::class)
fun playerErrorHttpStatus(error: PlaybackException?): Int? =
	(error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode

/**
 * The `detail` of a JSON error body, the way Tentacle explains a refused request
 * (`{"detail": "<channel> is not streaming right now"}`); null for anything else (#44).
 */
fun serverErrorDetail(body: ByteArray?): String? {
	if (body == null || body.isEmpty()) return null
	return try {
		Json.parseToJsonElement(body.decodeToString()).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
			?.trim()?.takeIf { it.isNotEmpty() && it.length <= 300 }
	} catch (_: Exception) {
		null
	}
}

/** The server's explanation of a failed request for a player error, if it sent one. */
@OptIn(UnstableApi::class)
fun playerErrorDetail(error: PlaybackException?): String? =
	serverErrorDetail((error?.cause as? HttpDataSource.InvalidResponseCodeException)?.responseBody)

/**
 * One line that says what failed (#19, #24): the error's name, the HTTP status and the request
 * path. Never the query, which carries the access token.
 */
@OptIn(UnstableApi::class)
fun describePlayerError(error: PlaybackException): String = buildString {
	append(error.errorCodeName)
	when (val cause = error.cause) {
		is HttpDataSource.InvalidResponseCodeException -> append(" HTTP ${cause.responseCode} ${cause.dataSpec.uri.path}")
		is HttpDataSource.HttpDataSourceException -> append(" ${cause.javaClass.simpleName} ${cause.dataSpec.uri.path}")
		null -> Unit
		else -> append(" ${cause.javaClass.simpleName}")
	}
}
