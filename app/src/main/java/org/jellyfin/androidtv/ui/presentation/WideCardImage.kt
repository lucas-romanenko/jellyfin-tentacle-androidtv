package org.jellyfin.androidtv.ui.presentation

import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.abs

private const val WIDE_ASPECT = 16f / 9f

/**
 * The picture on a wide (16:9) card, Jellyfin Web's choice for its thumb cards: the item's Thumb,
 * else its first Backdrop, else [fallback] (its Primary: an episode's still, a movie's poster).
 * A movie with only a poster and a backdrop showed its poster cropped into 16:9 before.
 * An episode with no picture of its own (a next episode in Continue Watching) takes its show's
 * thumb or backdrop, as Web does, instead of an empty card.
 */
internal fun wideCardImage(item: BaseItemDto, fallback: JellyfinImage?): JellyfinImage? =
	item.itemImages[ImageType.THUMB]
		?: item.itemBackdropImages.firstOrNull()
		?: fallback
		?: item.parentImages[ImageType.THUMB]
		?: item.parentBackdropImages.firstOrNull()

/**
 * Whether [image] fills a wide card (cropped to it) or is fitted whole inside it, on the card's
 * background. Thumbs and backdrops are 16:9; another image fills only when its aspect is within
 * 20% of the card's, like Web's `coverImage`, so a poster is never cut down to its middle.
 */
internal fun fillsWideCard(image: JellyfinImage): Boolean = when (image.type) {
	ImageType.THUMB, ImageType.BACKDROP -> true
	else -> image.aspectRatio?.let { abs(it - WIDE_ASPECT) / WIDE_ASPECT <= 0.2f } ?: false
}

/**
 * Whether [image] is wide (landscape) and so is shown whole on a poster card rather than cut down
 * to its middle third. Only for Live TV recordings whose Primary is still their 16:9 picture
 * (Tentacle's pictures off, or one a person chose): Tentacle gives recordings a 2:3 poster.
 */
internal fun isWideForPosterCard(image: JellyfinImage): Boolean =
	image.type == ImageType.THUMB || image.type == ImageType.BACKDROP || (image.aspectRatio ?: 0f) > 1.2f
