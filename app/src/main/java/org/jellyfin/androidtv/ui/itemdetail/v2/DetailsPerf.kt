package org.jellyfin.androidtv.ui.itemdetail.v2

/** The details backdrop's width when it is shown sharp or blurred by the GPU. */
const val DETAILS_BACKDROP_WIDTH = 1920

/**
 * How the details screen loads its backdrop. Before Android 12 a blurred backdrop is blurred on
 * the CPU: then it is asked for and decoded small (the blur hides the resolution, and a 1920 px
 * picture blurred on the CPU took the main thread for a long while on a weak TV), and the
 * ImageView scales it up.
 */
data class DetailsBackdropSpec(
	val cpuBlur: Boolean,
	val maxWidth: Int,
	val decodeWidth: Int,
	val decodeHeight: Int,
)

fun detailsBackdropSpec(sdkInt: Int, blurAmount: Int): DetailsBackdropSpec {
	// RenderEffect (Android 12, API 31) blurs on the GPU
	val cpuBlur = sdkInt < 31 && blurAmount > 0
	return if (cpuBlur) DetailsBackdropSpec(cpuBlur = true, maxWidth = 480, decodeWidth = 480, decodeHeight = 270)
	else DetailsBackdropSpec(cpuBlur = false, maxWidth = DETAILS_BACKDROP_WIDTH, decodeWidth = 1920, decodeHeight = 1080)
}

/**
 * Lazy-list keys for [keys] that stay unique when a key repeats (the same item twice in a row):
 * the first one keeps its key, later ones get "key#n". A repeated key crashes a lazy list.
 */
fun <K : Any> uniqueKeys(keys: List<K>): List<Any> {
	val seen = HashMap<K, Int>(keys.size)
	return keys.map { key ->
		val n = seen[key] ?: 0
		seen[key] = n + 1
		if (n == 0) key else "$key#$n"
	}
}
