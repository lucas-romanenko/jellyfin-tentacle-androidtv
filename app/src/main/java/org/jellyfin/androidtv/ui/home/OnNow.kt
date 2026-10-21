package org.jellyfin.androidtv.ui.home

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.time.Duration
import java.time.LocalDateTime

/**
 * On Now, Tentacle's own Live TV home row (Lucas, 2026-10-21): what's on right now on the
 * person's favourite channels (Jellyfin's favourites, the household lineup). Jellyfin answers
 * as that person, so a kid only gets kids channels. Replaces Jellyfin's Live TV rows.
 */
const val ON_NOW_SECTION_ID = "onnow"
const val ON_NOW_ROW_KEY = "builtin:onnow"
private const val ON_NOW_MAX = 30
/** How often a showing Home refills On Now (programmes end, time left moves on). */
val ON_NOW_REFRESH = kotlin.time.Duration.parse("60s")

/** Favourite channels with their current programme, as row items (empty = no row). */
suspend fun loadOnNow(api: ApiClient, userId: java.util.UUID): List<BaseItemDto> {
	val channels by api.liveTvApi.getLiveTvChannels(
		userId = userId,
		isFavorite = true,
		addCurrentProgram = true,
		enableUserData = true,
		imageTypeLimit = 1,
		enableImageTypes = listOf(ImageType.PRIMARY),
		limit = 100,
	)
	return onNowItems(channels.items, LocalDateTime.now())
}

/**
 * The channels with something on at [now], each a copy of the channel (so the card tunes in
 * like any channel and shows its logo whole) named after the programme, with the channel's
 * name and the time left as its sub line. The SDK's dates are local, like [now].
 */
fun onNowItems(channels: List<BaseItemDto>, now: LocalDateTime): List<BaseItemDto> = channels
	.mapNotNull { channel ->
		val program = channel.currentProgram ?: return@mapNotNull null
		val end = program.endDate ?: return@mapNotNull null
		val start = program.startDate
		if (!end.isAfter(now) || (start != null && start.isAfter(now))) return@mapNotNull null
		channel.copy(
			name = program.name?.takeIf { it.isNotBlank() } ?: channel.name,
			number = listOfNotNull(channel.name, timeLeft(now, end)).joinToString(" · "),
		)
	}
	.take(ON_NOW_MAX)

/** "19m left", "1h 5m left" (never "0m"). */
fun timeLeft(now: LocalDateTime, end: LocalDateTime): String {
	val minutes = maxOf(1L, (Duration.between(now, end).seconds + 59) / 60)
	return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left"
}
