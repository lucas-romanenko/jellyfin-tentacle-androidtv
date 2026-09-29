@file:JvmName("ChannelStep")

package org.jellyfin.androidtv.ui.livetv

/**
 * The channel a TV remote's Channel Up / Channel Down key moves to, in the guide's order,
 * wrapping at either end (#56). Up is the next channel in the list. -1 when there are no
 * channels; the first channel when the current one isn't in the list.
 */
fun steppedChannelIndex(current: Int, count: Int, up: Boolean): Int = when {
	count <= 0 -> -1
	current !in 0 until count -> 0
	up -> (current + 1) % count
	else -> (current - 1 + count) % count
}
