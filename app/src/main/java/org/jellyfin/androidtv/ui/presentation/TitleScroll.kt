package org.jellyfin.androidtv.ui.presentation

import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The horizontal scroll that shows a centred single-line text from its start, as TextView sets
 * it after a layout: centred when it fits in [width], else its leading edge ([left] for
 * left-to-right text, [right] for right-to-left) at the edge of the view. [left] and [right] are
 * the line's edges in the text's layout, which is much wider than the view.
 */
internal fun textStartScrollX(left: Int, right: Int, width: Int, rightToLeft: Boolean): Int = when {
	right - left < width -> (right + left) / 2 - width / 2
	rightToLeft -> right - width
	else -> left
}

/** Scrolls a centred single-line text back to its start (no-op before its first layout). */
internal fun TextView.scrollToTextStart() {
	val layout = layout ?: return
	if (layout.lineCount == 0) return
	val width = width - compoundPaddingLeft - compoundPaddingRight
	if (width <= 0) return
	val x = textStartScrollX(
		left = floor(layout.getLineLeft(0)).toInt(),
		right = ceil(layout.getLineRight(0)).toInt(),
		width = width,
		rightToLeft = layout.getParagraphDirection(0) < 0,
	)
	if (x != scrollX) scrollTo(x, scrollY)
}
