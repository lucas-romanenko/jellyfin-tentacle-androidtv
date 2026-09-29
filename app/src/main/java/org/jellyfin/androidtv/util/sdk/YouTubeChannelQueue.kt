package org.jellyfin.androidtv.util.sdk

/*
 * Tentacle's YouTube library keeps playing the channel after a video ends (#42). Every video
 * is its own Movie item in its own folder, so the channel is found by the tag `yt:<slug>`
 * Tentacle writes into each video's NFO, not by the parent folder.
 */

/** Longest queue built for a channel. */
const val YOUTUBE_QUEUE_LIMIT = 50

/** The `yt:<slug>` tag naming a Tentacle YouTube video's channel, or null. */
fun youtubeChannelTag(tags: List<String>?): String? = tags
	?.map { it.trim() }
	?.firstOrNull { it.length > 3 && it.startsWith("yt:", ignoreCase = true) }

/** [items] from the chosen one on; null if it isn't among them. */
fun <T> queueFrom(items: List<T>, isChosen: (T) -> Boolean): List<T>? {
	val index = items.indexOfFirst(isChosen)
	return if (index < 0) null else items.drop(index)
}
