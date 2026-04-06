package org.jellyfin.androidtv.ui.browsing.genre

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.leanback.widget.Presenter
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import org.jellyfin.androidtv.R

/**
 * Presenter for displaying Jellyfin genre cards with backdrop images.
 * Similar to Jellyseerr's GenreCardPresenter but uses Jellyfin's image API.
 */
class JellyfinGenreCardPresenter : Presenter() {
	private val cardWidthDp = 220
	private val cardHeightDp = 110

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val cardView = LayoutInflater.from(parent.context)
			.inflate(R.layout.jellyfin_genre_card, parent, false) as CardView

		cardView.isFocusable = true
		cardView.isFocusableInTouchMode = true

		val density = parent.context.resources.displayMetrics.density
		val widthPx = (cardWidthDp * density).toInt()
		val heightPx = (cardHeightDp * density).toInt()
		
		cardView.layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
			setMargins(
				(4 * density).toInt(),
				(4 * density).toInt(),
				(4 * density).toInt(),
				(4 * density).toInt()
			)
		}

		return ViewHolder(cardView)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		val genre = item as? JellyfinGenreItem ?: return
		val cardView = viewHolder.view as CardView
		val context = cardView.context

		val backdropView = cardView.findViewById<ImageView>(R.id.genre_backdrop)
		val titleView = cardView.findViewById<TextView>(R.id.genre_title)
		val countView = cardView.findViewById<TextView>(R.id.genre_count)

		titleView.text = genre.name

		if (genre.itemCount > 0) {
			countView.visibility = View.VISIBLE
			countView.text = context.resources.getQuantityString(
				R.plurals.genre_item_count, 
				genre.itemCount, 
				genre.itemCount
			)
		} else {
			countView.visibility = View.GONE
		}

		// Load backdrop image if available
		if (!genre.backdropUrl.isNullOrEmpty()) {
			backdropView.load(genre.backdropUrl) {
				crossfade(true)
				placeholder(R.drawable.default_genre_backdrop)
				error(R.drawable.default_genre_backdrop)
			}
		} else {
			// Use a default gradient background when no backdrop is available
			backdropView.setImageResource(R.drawable.default_genre_backdrop)
		}
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		val cardView = viewHolder.view as CardView
		val backdropView = cardView.findViewById<ImageView>(R.id.genre_backdrop)
		backdropView.setImageDrawable(null)
	}
}
