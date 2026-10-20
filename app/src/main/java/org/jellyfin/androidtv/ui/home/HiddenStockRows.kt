package org.jellyfin.androidtv.ui.home

/**
 * Built-in rows (Continue Watching, Next Up, …) remove themselves from Home when they come back
 * empty. Home still asks them again when something they show changed, and puts them back in
 * their place once they have items: a row that left empty never came back until the app
 * restarted (#140).
 */
internal object HiddenStockRows {
	/** The rows to ask again: those on screen, then the built-in ones that left. */
	fun <T> toRecheck(shown: List<T>, stock: List<T>): List<T> = shown + stock.filterNot { it in shown }

	/**
	 * Where [row] goes back among the rows [shown]: before the next row of the home [order] that is
	 * shown, else after the previous one, else at the end.
	 */
	fun <T> returnIndex(order: List<T>, row: T, shown: List<T>): Int {
		val at = order.indexOf(row)
		if (at < 0) return shown.size
		order.drop(at + 1).firstOrNull { it in shown }?.let { return shown.indexOf(it) }
		order.take(at).lastOrNull { it in shown }?.let { return shown.indexOf(it) + 1 }
		return shown.size
	}
}
