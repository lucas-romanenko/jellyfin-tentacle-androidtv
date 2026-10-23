package org.jellyfin.androidtv.ui.home

/**
 * The card of a row the focus is on after Home's rows were refreshed in place, from the row's
 * selected position ([selected], null when the row has no view) and its size now; null when
 * it can't be told. The refresh used to take the row's first card instead, so a live update
 * put the first title's backdrop and name behind the focused card (#14).
 */
fun focusedCardIndex(selected: Int?, size: Int): Int? = when {
	selected == null || selected < 0 || size <= 0 -> null
	// The row got shorter than where the focus was: Leanback moves it to the last card
	selected >= size -> size - 1
	else -> selected
}
