package org.jellyfin.androidtv.ui.presentation

import android.graphics.Outline
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import kotlin.math.roundToInt

/**
 * A button tile (Home's Live TV row, browse screens): plain views, like the cards, so a row of
 * them costs no AndroidComposeView per tile.
 */
class GridButtonPresenter @JvmOverloads constructor(
	private val width: Int = 110,
	private val imageHeight: Int = 110,
) : Presenter() {
	inner class ViewHolder(
		private val root: FrameLayout,
		private val image: ImageView,
		private val text: TextView,
	) : Presenter.ViewHolder(root) {
		fun bind(value: GridButton) {
			if (value.imageRes != null) {
				image.visibility = View.VISIBLE
				image.setImageResource(value.imageRes)
				image.contentDescription = value.text
			} else {
				image.visibility = View.GONE
				image.setImageDrawable(null)
			}
			text.text = value.text
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val context = parent.context
		val density = context.resources.displayMetrics.density
		fun dp(value: Int) = (value * density).roundToInt()

		val root = FrameLayout(context).apply {
			isFocusable = true
			isFocusableInTouchMode = true
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			setBackgroundColor(ContextCompat.getColor(context, R.color.button_default_normal_background))
			outlineProvider = object : ViewOutlineProvider() {
				override fun getOutline(view: View, outline: Outline) {
					outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
				}
			}
			clipToOutline = true
			layoutParams = ViewGroup.LayoutParams(dp(width), ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
		val text = TextView(context).apply {
			setTextColor(ContextCompat.getColor(context, R.color.button_default_normal_text))
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
			setPadding(dp(15), dp(10), dp(15), dp(10))
		}
		root.addView(image, FrameLayout.LayoutParams(dp(width), dp(imageHeight)))
		root.addView(
			text,
			FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM or Gravity.START,
			),
		)
		return ViewHolder(root, image, text)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return

		when (item) {
			is GridButtonBaseRowItem -> viewHolder.bind(item.gridButton)
			is GridButton -> viewHolder.bind(item)
		}
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
	override fun onViewAttachedToWindow(viewHolder: Presenter.ViewHolder) = Unit
}
