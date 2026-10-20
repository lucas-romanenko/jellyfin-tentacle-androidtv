package org.jellyfin.androidtv.ui.itemhandling

/**
 * How a row refresh tells its cards apart (M2): the same card is the same item, and it is
 * unchanged when the item's data is equal (a played flag, a resume point or a new image
 * changes it). A refresh then touches only the cards that changed: the same answer again
 * changes nothing on screen, where clearing and re-adding every card flickered, reloaded
 * the images and moved the focus.
 */
object CardDiff {
	@JvmStatic
	fun sameCard(old: Any, new: Any): Boolean =
		if (old is BaseRowItem && new is BaseRowItem && old.itemId != null) old.itemId == new.itemId
		else old == new

	@JvmStatic
	fun sameContent(old: Any, new: Any): Boolean =
		if (old is BaseRowItem && new is BaseRowItem) old.baseItem == new.baseItem
		else old == new
}
