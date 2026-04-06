package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.ProgramGridCellBinding
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment
import org.jellyfin.androidtv.util.Utils

class GuidePagingButton : RelativeLayout {

	@JvmOverloads
	constructor(
		context: Context,
		attrs: AttributeSet? = null
	) : super(context, attrs)

	constructor(
		context: Context,
		guide: LiveTvGuide,
		start: Int,
		label: String
	) : super(context) {
		val inflater = LayoutInflater.from(context)
		val binding = ProgramGridCellBinding.inflate(inflater, this, true)
		binding.programName.text = label

		setBackgroundColor(Utils.getThemeColor(context, R.attr.buttonDefaultNormalBackground))
		isFocusable = true
		setOnClickListener { guide.displayChannels(start, LiveTvGuideFragment.PAGE_SIZE) }
	}

	override fun onFocusChanged(hasFocus: Boolean, direction: Int, previouslyFocused: Rect?) {
		super.onFocusChanged(hasFocus, direction, previouslyFocused)

		setBackgroundColor(
			Utils.getThemeColor(
				context,
				if (hasFocus) android.R.attr.colorAccent else R.attr.buttonDefaultNormalBackground
			)
		)
	}
}
