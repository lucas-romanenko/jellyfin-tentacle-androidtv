package org.jellyfin.androidtv.ui.presentation

import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.abs

private const val WIDE_ASPECT = 16f / 9f

/**
 * The picture on a wide (16:9) card, Jellyfin Web's choice for its thumb cards: the item's Thumb,
 * else its first Backdrop, else [fallback] (its Primary: an episode's still, a movie's poster).
 * A movie with only a poster and a backdrop showed its poster cropped into 16:9 before.
 */
internal fun wideCardImage(item: BaseItemDto, fallback: JellyfinImage?): JellyfinImage? =
	item.itemImages[ImageType.THUMB]
		?: item.itemBackdropImages.firstOrNull()
		?: fallback

/**
 * Whether [image] fills a wide card (cropped to it) or is fitted whole inside it, on the card's
 * background. Thumbs and backdrops are 16:9; another image fills only when its aspect is within
 * 20% of the card's, like Web's `coverImage`, so a poster is never cut down to its middle.
 */
internal fun fillsWideCard(image: JellyfinImage): Boolean = when (image.type) {
	ImageType.THUMB, ImageType.BACKDROP -> true
	else -> image.aspectRatio?.let { abs(it - WIDE_ASPECT) / WIDE_ASPECT <= 0.2f } ?: false
}
