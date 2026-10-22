package org.jellyfin.androidtv.ui.browsing.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.ceil
import kotlin.math.max

// Shared helpers that keep the v2 browse screens light on a weak TV: the focused item lives
// outside the screen's uiState (a D-pad press recomposes only the header), backgrounds wait
// for the focus to settle, and card images are requested at the card's own pixel size.

/** How long the focus has to rest on a card before its backdrop is loaded. */
const val FOCUS_BACKGROUND_DEBOUNCE_MS = 400L

/**
 * Runs only the last action submitted within [delayMs]: scrolling past ten cards loads one
 * backdrop instead of ten. Lives in a composition's scope, so a pending action is dropped when
 * the screen goes away.
 */
class FocusDebouncer(
	private val scope: CoroutineScope,
	private val delayMs: Long = FOCUS_BACKGROUND_DEBOUNCE_MS,
) {
	private var job: Job? = null

	fun submit(action: () -> Unit) {
		job?.cancel()
		job = scope.launch {
			delay(delayMs)
			action()
		}
	}
}

@Composable
fun rememberFocusDebouncer(delayMs: Long = FOCUS_BACKGROUND_DEBOUNCE_MS): FocusDebouncer {
	val scope = rememberCoroutineScope()
	return remember(scope, delayMs) { FocusDebouncer(scope, delayMs) }
}

/**
 * [FocusedItemHud] reading the focused item from its own flow: only this HUD recomposes when the
 * focus moves, not the screen around it.
 */
@Composable
fun FocusedItemHud(
	itemFlow: StateFlow<BaseItemDto?>,
	modifier: Modifier = Modifier,
) {
	val item by itemFlow.collectAsState()
	FocusedItemHud(item = item, modifier = modifier)
}

/**
 * The image height to ask Jellyfin for so the picture covers (ContentScale.Crop) a card of
 * [cardWidthPx] x [cardHeightPx]: the card's height, or more when the picture is narrower than
 * the card (it is scaled by width then). Without a known aspect ratio the larger card side is
 * enough for any picture between square and the card's shape.
 */
fun cardImageMaxHeight(cardWidthPx: Int, cardHeightPx: Int, imageAspectRatio: Float?): Int {
	val width = cardWidthPx.coerceAtLeast(1)
	val height = cardHeightPx.coerceAtLeast(1)
	if (imageAspectRatio == null || imageAspectRatio <= 0f) return max(width, height)
	// A very tall picture in a wide card would otherwise ask for a huge image.
	val needed = ceil(width / imageAspectRatio.toDouble()).toInt()
	return max(height, needed).coerceAtMost(max(width, height) * 2)
}

/** The aspect ratio of [image] if Jellyfin told us, else the usual one for its type. */
fun expectedImageAspectRatio(image: JellyfinImage): Float? = image.aspectRatio ?: when (image.type) {
	ImageType.THUMB, ImageType.BACKDROP -> 16f / 9f
	ImageType.BANNER -> 1000f / 185f
	else -> null
}

/** The URL of [image] sized for a card of [cardWidthPx] x [cardHeightPx]. */
fun JellyfinImage.getCardUrl(api: ApiClient, cardWidthPx: Int, cardHeightPx: Int): String =
	getUrl(api, maxHeight = cardImageMaxHeight(cardWidthPx, cardHeightPx, expectedImageAspectRatio(this)))

/** A card's size in pixels, for [getCardUrl]. */
data class CardPx(val width: Int, val height: Int)

@Composable
fun rememberCardPx(cardWidthDp: Int, cardHeightDp: Int): CardPx {
	val density = LocalDensity.current
	return remember(density, cardWidthDp, cardHeightDp) {
		with(density) { CardPx(cardWidthDp.dp.roundToPx(), cardHeightDp.dp.roundToPx()) }
	}
}

/**
 * [existing] followed by the items of [new] not already in it (by [key]). Paged results can
 * repeat an item when the library changes between pages, and a lazy grid keyed by id must not
 * see the same key twice.
 */
fun <T, K> appendDistinctBy(existing: List<T>, new: List<T>, key: (T) -> K): List<T> {
	val seen = HashSet<K>(existing.size + new.size)
	existing.forEach { seen += key(it) }
	val added = new.filter { seen.add(key(it)) }
	return if (added.isEmpty()) existing else existing + added
}

/** [items] without repeats, so they can be keyed by id in a lazy list. */
fun List<BaseItemDto>.distinctById(): List<BaseItemDto> =
	if (size < 2) this else distinctBy { it.id }
