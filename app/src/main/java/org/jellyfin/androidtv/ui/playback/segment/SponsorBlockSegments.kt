package org.jellyfin.androidtv.ui.playback.segment

import org.jellyfin.androidtv.ui.home.mediabar.SponsorBlockApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID

/*
 * SponsorBlock for Tentacle's YouTube library (#43). Those items carry the video id as the
 * provider id "youtube" (written from the NFO's uniqueid). When Jellyfin has no segments for
 * such an item, SponsorBlock's are fetched and handled like media segments, with the user's
 * per-type actions (Settings > Playback > Media segments), so nothing new to configure.
 */

private val youtubeIdPattern = Regex("^[A-Za-z0-9_-]{11}$")

/** The YouTube video id of a Tentacle YouTube item, or null. The key's case varies. */
fun youtubeVideoId(providerIds: Map<String, String?>?): String? = providerIds
	?.entries
	?.firstOrNull { it.key.equals("youtube", ignoreCase = true) }
	?.value
	?.trim()
	?.takeIf { youtubeIdPattern.matches(it) }

/** The media segment type a SponsorBlock category is handled as; null for categories left alone. */
fun sponsorBlockSegmentType(category: String): MediaSegmentType? = when (category) {
	"sponsor", "selfpromo", "interaction" -> MediaSegmentType.COMMERCIAL
	"intro" -> MediaSegmentType.INTRO
	"outro" -> MediaSegmentType.OUTRO
	"preview" -> MediaSegmentType.PREVIEW
	else -> null
}

/** The SponsorBlock categories that map to a media segment type. */
val sponsorBlockCategories = listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview")

/** SponsorBlock's skip segments as media segments of [itemId]. Mute and highlight entries are left out. */
fun sponsorBlockToMediaSegments(itemId: UUID, segments: List<SponsorBlockApi.Segment>): List<MediaSegmentDto> =
	segments.mapNotNull { segment ->
		if (segment.actionType != "skip") return@mapNotNull null
		if (segment.endTime <= segment.startTime) return@mapNotNull null
		val type = sponsorBlockSegmentType(segment.category) ?: return@mapNotNull null
		MediaSegmentDto(
			id = UUID.nameUUIDFromBytes("$itemId:${segment.category}:${segment.startTime}".toByteArray()),
			itemId = itemId,
			type = type,
			startTicks = (segment.startTime * TICKS_PER_SECOND).toLong(),
			endTicks = (segment.endTime * TICKS_PER_SECOND).toLong(),
		)
	}

private const val TICKS_PER_SECOND = 10_000_000.0
