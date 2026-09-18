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
	private val adapterRegistry: MutableMap<String, ItemRowAdapter>? = null,
) : HomeFragmentRow {

	private companion object {
		// THUMB is the 16:9 image type; CardPresenter maps it to
		// ASPECT_RATIO_16_9. Built once and shared for the same reason the
		// poster presenter is: view recycling across rows.
		val widePresenter by lazy {
			CardPresenter(true, org.jellyfin.androidtv.constant.ImageType.THUMB, 150, true)
		}
	}

	override fun addToRowsAdapter(
		context: Context,
		cardPresenter: CardPresenter,
		rowsAdapter: MutableObjectAdapter<Row>,
	) {
		for (rowData in rowDataList) {
			if (rowData.items.isEmpty()) continue

			// Share a presenter per shape, so the RecycledViewPool is still shared
			// across rows of the same shape rather than one per row.
			val presenter = if (rowData.wide) widePresenter else cardPresenter
			val rowAdapter = ItemRowAdapter(
				context,
				rowData.items,
				presenter,
				rowsAdapter,
				true, // staticItems flag
			)

			val header = HeaderItem(rowData.title)
			val row = ListRow(header, rowAdapter)
			rowAdapter.setRow(row)
			rowAdapter.Retrieve()
			rowsAdapter.add(row)

			// Register adapter for in-place refresh
			adapterRegistry?.put(rowData.playlistId, rowAdapter)

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
	/** 16:9 cards instead of 2:3. Set from the row's shape in the dashboard. */
	val wide: Boolean = false,
)
