package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber

/**
 * Home screen row that displays MediaHub curated playlist content.
 *
 * Items are pre-fetched on the IO thread and passed in as [MediaHubRowData].
 * Each entry becomes a standard Leanback ListRow with Jellyfin BaseItemDto
 * objects, so clicking items navigates to the normal detail screen.
 */
class HomeFragmentMediaHubRow(
	private val rowDataList: List<MediaHubRowData>,
) : HomeFragmentRow {

	override fun addToRowsAdapter(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
	) {
		for (rowData in rowDataList) {
			if (rowData.items.isEmpty()) continue

			// Use the StaticItems constructor — items are already fetched
			val rowAdapter = ItemRowAdapter(
				context,
				rowData.items,
				cardPresenter,
				rowsAdapter,
				true, // staticItems flag
			)

			val header = HeaderItem(rowData.title)
			val row = ListRow(header, rowAdapter)
			rowAdapter.setRow(row)
			rowAdapter.Retrieve()
			rowsAdapter.add(row)

			Timber.d("Added MediaHub row '${rowData.title}' with ${rowData.items.size} items")
		}
	}
}

/**
 * Pre-fetched data for a single MediaHub home screen row.
 */
data class MediaHubRowData(
	val title: String,
	val playlistId: String,
	val items: List<BaseItemDto>,
)
