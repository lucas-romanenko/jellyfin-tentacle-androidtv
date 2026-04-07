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
 * Home screen row that displays Tentacle curated playlist content.
 *
 * Items are pre-fetched on the IO thread and passed in as [TentacleRowData].
 * Each entry becomes a standard Leanback ListRow with Jellyfin BaseItemDto
 * objects, so clicking items navigates to the normal detail screen.
 */
class HomeFragmentTentacleRow(
	private val rowDataList: List<TentacleRowData>,
) : HomeFragmentRow {

	override fun addToRowsAdapter(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
	) {
		// Use a uniform poster presenter for consistent card sizing
		val posterPresenter = CardPresenter(true, org.jellyfin.androidtv.constant.ImageType.POSTER, 150, true)

		for (rowData in rowDataList) {
			if (rowData.items.isEmpty()) continue

			// Use the StaticItems constructor — items are already fetched
			val rowAdapter = ItemRowAdapter(
				context,
				rowData.items,
				posterPresenter,
				rowsAdapter,
				true, // staticItems flag
			)

			val header = HeaderItem(rowData.title)
			val row = ListRow(header, rowAdapter)
			rowAdapter.setRow(row)
			rowAdapter.Retrieve()
			rowsAdapter.add(row)

			Timber.d("Added Tentacle row '${rowData.title}' with ${rowData.items.size} items")
		}
	}
}

/**
 * Pre-fetched data for a single Tentacle home screen row.
 */
data class TentacleRowData(
	val title: String,
	val playlistId: String,
	val items: List<BaseItemDto>,
)
