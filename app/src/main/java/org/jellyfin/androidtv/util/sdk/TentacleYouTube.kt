package org.jellyfin.androidtv.util.sdk

import org.jellyfin.androidtv.ui.playback.segment.youtubeVideoId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/*
 * Tentacle puts every YouTube video into Jellyfin as a Movie: a .strm and an NFO with the genre
 * "YouTube", the tags "youtube" and "yt:<slug>", the video id as uniqueid "youtube" and the
 * channel as the studio, in <root>/<Channel>/<YYYY-MM-DD Title [videoid]>/. Cards and meta lines
 * show "YouTube · <channel>" for them instead of "Movie • 2026".
 *
 * Which of these an item carries depends on the fields asked for: Tentacle's home rows have
 * Genres and Path, the stock rows and grids also ProviderIds, the details page everything. So
 * any one of them marks the item, and the channel comes from the studio, else the folder name.
 */

private const val YOUTUBE = "YouTube"

// "2026-10-01 Some title [dQw4w9WgXcQ]", the folder of one video.
private val videoFolderPattern = Regex("""^\d{4}-\d{2}-\d{2} .+ \[[A-Za-z0-9_-]{11}]$""")

/** The channel folder of a Tentacle YouTube .strm path, or null if [path] isn't one. */
fun youTubeChannelFromPath(path: String?): String? {
	if (path == null || !path.endsWith(".strm", ignoreCase = true)) return null
	val parts = path.split('/', '\\').filter { it.isNotEmpty() }
	if (parts.size < 3 || !videoFolderPattern.matches(parts[parts.size - 2])) return null
	return parts[parts.size - 3].trim().takeIf { it.isNotEmpty() }
}

/** True for a video from Tentacle's YouTube library. */
fun isTentacleYouTube(
	type: BaseItemKind?,
	genres: List<String>?,
	tags: List<String>?,
	providerIds: Map<String, String?>?,
	path: String?,
): Boolean {
	if (type != BaseItemKind.MOVIE && type != BaseItemKind.VIDEO) return false
	return genres.orEmpty().any { it.trim().equals(YOUTUBE, ignoreCase = true) }
		|| tags.orEmpty().any { it.trim().equals(YOUTUBE, ignoreCase = true) }
		|| youtubeVideoId(providerIds) != null
		|| youTubeChannelFromPath(path) != null
}

/** The line under a YouTube video: "YouTube · <channel>", or null when the channel is unknown. */
fun youTubeSubtitle(channel: String?): String? =
	channel?.trim()?.takeIf { it.isNotEmpty() }?.let { "$YOUTUBE · $it" }

val BaseItemDto.isTentacleYouTube: Boolean
	get() = isTentacleYouTube(type, genres, tags, providerIds, path)

/** The channel of a Tentacle YouTube video: its studio, else its channel folder. */
val BaseItemDto.youTubeChannel: String?
	get() = studios?.firstNotNullOfOrNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
		?: youTubeChannelFromPath(path)

/** "YouTube · <channel>" for a Tentacle YouTube video, null for one without a known channel. */
val BaseItemDto.youTubeSubtitle: String?
	get() = youTubeSubtitle(youTubeChannel)
