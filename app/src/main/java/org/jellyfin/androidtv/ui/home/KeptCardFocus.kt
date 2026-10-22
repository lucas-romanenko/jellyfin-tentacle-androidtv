package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import timber.log.Timber

/**
 * Where the focus goes in a row whose cards were replaced (a live update changed the row's
 * shape, or the row moved): the card of the same item ([focusedKey], found in [keys]), else
 * the same place, or the last card when the row got shorter. Null for an empty row.
 */
fun <K> keptCardIndex(focusedKey: K?, focusedIndex: Int, keys: List<K?>): Int? {
	if (keys.isEmpty() || focusedIndex < 0) return null
	if (focusedKey != null) keys.indexOf(focusedKey).takeIf { it >= 0 }?.let { return it }
	return focusedIndex.coerceAtMost(keys.lastIndex)
}

/**
 * The cards of a reshaped row whose pictures are fetched before it goes up: the ones on screen
 * around the focused card ([focused], the first card when the row has no focus), [count] of them.
 */
fun cardsToWarm(size: Int, focused: Int?, count: Int = WARM_CARDS): IntRange {
	if (size <= 0) return IntRange.EMPTY
	val start = ((focused ?: 0) - 2).coerceIn(0, size - 1)
	return start until minOf(size, start + count)
}

private const val WARM_CARDS = 10

/** The focused card of Home's rows: its row, the row's key and the card's item and place. */
internal class KeptCard(val row: ListRow, val rowKey: String?, val itemKey: Any?, val index: Int)

/** The card the focus is on in the selected row, when that row is a list of cards. */
internal fun RowsSupportFragment.focusedCard(rowKey: (Row) -> String?): KeptCard? {
	val rows = adapter ?: return null
	val position = selectedPosition
	if (position !in 0 until rows.size()) return null
	val row = rows.get(position) as? ListRow ?: return null
	val cards = row.adapter ?: return null
	val index = (getRowViewHolder(position) as? ListRowPresenter.ViewHolder)?.gridView?.selectedPosition ?: return null
	if (index !in 0 until cards.size()) return null
	return KeptCard(row, rowKey(row), (cards.get(index) as? BaseRowItem)?.itemId, index)
}

/**
 * Home's rows were just replaced ([rows], the rows adapter's new list): put the focus back on
 * [card], in its row (the same row, or the one of the same key) on the card of the same item.
 * A row rebuilt for a new card shape has a new list of cards, which Leanback starts at the
 * first card: the focus jumped there from the card it was on. Done in the layout that shows
 * the new rows, before they are drawn; dropped if that layout has the focus on another row.
 */
internal fun RowsSupportFragment.keepFocusedCard(card: KeptCard, rows: List<Row>, rowKey: (Row) -> String?) {
	val grid = verticalGridView ?: return
	val rowPosition = rows.indexOfFirst { it === card.row || (card.rowKey != null && rowKey(it) == card.rowKey) }
	if (rowPosition < 0) return
	val cards = (rows[rowPosition] as? ListRow)?.adapter ?: return
	val index = keptCardIndex(card.itemKey, card.index, cards.itemKeys()) ?: return

	grid.addOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
		override fun onChildViewHolderSelectedAndPositioned(
			parent: RecyclerView,
			child: RecyclerView.ViewHolder?,
			position: Int,
			subposition: Int,
		) {
			grid.removeOnChildViewHolderSelectedListener(this)
			if (position != rowPosition) return
			val bridged = child as? ItemBridgeAdapter.ViewHolder ?: return
			val rowHolder = (bridged.presenter as? RowPresenter)?.getRowViewHolder(bridged.viewHolder) as? ListRowPresenter.ViewHolder
				?: return
			if (rowHolder.row !== rows[rowPosition]) return
			if (rowHolder.gridView.selectedPosition != index) {
				Timber.i("Home rows replaced: focus kept on card $index (was ${card.index})")
				rowHolder.gridView.selectedPosition = index
			}
		}
	})
}

private fun ObjectAdapter.itemKeys(): List<Any?> = (0 until size()).map { (get(it) as? BaseRowItem)?.itemId }

/**
 * Fetches the pictures of a row's cards for their new shape into the memory cache, before the
 * row is rebuilt with them: the new cards then show their pictures at once (AsyncImageView reads
 * the memory cache) instead of empty frames for half a second. At most [timeoutMs]: a slow
 * server shows the new shape anyway, its cards filling in as before.
 */
internal suspend fun warmCardImages(context: Context, imageLoader: ImageLoader, urls: List<String>, timeoutMs: Long = 1_500) {
	if (urls.isEmpty()) return
	val started = System.nanoTime()
	val done = withTimeoutOrNull(timeoutMs) {
		withContext(Dispatchers.IO) {
			coroutineScope {
				urls.distinct().map { url ->
					async {
						try {
							imageLoader.execute(ImageRequest.Builder(context).data(url).build())
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							Timber.w(e, "Card picture not fetched ahead")
						}
					}
				}.awaitAll()
			}
		}
	}
	Timber.i("Reshaped row: ${urls.size} card pictures fetched ahead in ${(System.nanoTime() - started) / 1_000_000} ms${if (done == null) " (timed out)" else ""}")
}

/** The pictures the cards [range] of [cards] load when shown by [presenter]. */
internal fun cardImageUrls(context: Context, presenter: CardPresenter, cards: ObjectAdapter, range: IntRange): List<String> =
	range.mapNotNull { (cards.get(it) as? BaseRowItem)?.let { item -> presenter.imageUrl(context, item) } }
