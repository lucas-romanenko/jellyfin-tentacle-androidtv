package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

class ObservableScrollView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyle: Int = 0
) : ScrollView(context, attrs, defStyle) {

	var scrollViewListener: ScrollViewListener? = null

	override fun onScrollChanged(x: Int, y: Int, oldx: Int, oldy: Int) {
		super.onScrollChanged(x, y, oldx, oldy)
		scrollViewListener?.onScrollChanged(this, x, y, oldx, oldy)
	}
}
