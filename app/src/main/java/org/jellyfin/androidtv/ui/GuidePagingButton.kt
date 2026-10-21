package org.jellyfin.androidtv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.databinding.ProgramGridCellBinding
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment

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

		setBackgroundColor(ContextCompat.getColor(context, R.color.guide_chip_bg))
		isFocusable = true
		setOnClickListener { guide.displayChannels(start, LiveTvGuideFragment.PAGE_SIZE) }
	}

	override fun onFocusChanged(hasFocus: Boolean, direction: Int, previouslyFocused: Rect?) {
		super.onFocusChanged(hasFocus, direction, previouslyFocused)

		setBackgroundColor(
			ContextCompat.getColor(context, if (hasFocus) R.color.tentacle_accent else R.color.guide_chip_bg)
		)
	}
}
