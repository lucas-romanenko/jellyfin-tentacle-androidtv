package org.jellyfin.androidtv.ui.presentation

import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.sdk.model.api.BaseItemKind

/** A card's image size in dp. */
internal data class CardSizeDp(val width: Float, val height: Float)

/**
 * The image size of a card: [staticHeight] rows use the poster size setting (a shorter height
 * for landscape cards, so they don't dominate the row next to posters), other rows a fixed one.
 * [presenterHeight] 150 is the presenters' "use the poster size setting" value.
 */
internal fun cardSizeDp(
	aspectRatio: Float,
	staticHeight: Boolean,
	presenterHeight: Int,
	posterHeight: Int,
	posterLandscapeHeight: Int,
): CardSizeDp {
	val portraitHeight = if (presenterHeight == 150) posterHeight else presenterHeight
	val landscapeHeight = if (presenterHeight == 150) posterLandscapeHeight else (presenterHeight * 0.73f).toInt()
	val height = when {
		staticHeight && aspectRatio > 1f -> landscapeHeight
		staticHeight -> portraitHeight
		aspectRatio > 1f -> 130
		else -> 150
	}.toFloat()
	return CardSizeDp(height * aspectRatio, height)
}

/** What the top-right corner of a card shows. */
internal sealed interface WatchBadge {
	data object None : WatchBadge
	data object Played : WatchBadge
	data class Unplayed(val count: Int) : WatchBadge
}

internal fun watchBadge(
	behavior: WatchedIndicatorBehavior,
	type: BaseItemKind?,
	played: Boolean,
	unplayedCount: Int?,
): WatchBadge = when {
	behavior == WatchedIndicatorBehavior.NEVER -> WatchBadge.None
	behavior == WatchedIndicatorBehavior.EPISODES_ONLY && type != BaseItemKind.EPISODE -> WatchBadge.None
	played -> WatchBadge.Played
	unplayedCount != null && unplayedCount > 0 && behavior != WatchedIndicatorBehavior.HIDE_UNWATCHED ->
		WatchBadge.Unplayed(unplayedCount)
	else -> WatchBadge.None
}

/** The progress bar's fill (0..1), or null when there is no bar: not started or finished. */
internal fun playedFraction(playedPercentage: Double?): Float? =
	playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)?.takeIf { it > 0f && it < 1f }

/**
 * Server-side "card previews" policy (androidtv#47): "off" disables card previews, "local_only"
 * allows them only for local files (a provider .strm preview opens an IPTV connection), "all"
 * or an unknown value from a newer server allows them.
 */
internal fun previewAllowedByServer(policy: String, hasLocalMedia: Boolean): Boolean = when (policy) {
	"off" -> false
	"local_only" -> hasLocalMedia
	else -> true
}
