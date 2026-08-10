package org.jellyfin.androidtv.ui.card

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import org.jellyfin.androidtv.databinding.ViewCardItemBinding
import org.jellyfin.androidtv.util.dp
import kotlin.math.roundToInt

/**
 * Plain-View poster card used by [org.jellyfin.androidtv.ui.presentation.CardPresenter].
 *
 * This replaces the previous per-card ComposeView: composition/bind cost per card dominated
 * frame time during row scrolling on weak TV GPUs, so the standard card path is now ordinary
 * Android Views. The only Compose usage left is the episode/trailer preview overlay, which the
 * presenter lazily attaches into [previewContainer] while the card is focused.
 *
 * Focus notes:
 * - Leanback's `FocusHighlightHelper` overwrites any [android.view.View.OnFocusChangeListener]
 *   set by a Presenter (via `FocusAnimator` during binding). The [onFocusChanged] override is a
 *   protected [View] method that always fires, so [focusCallback] is reliable.
 * - Card zoom on focus is handled by Leanback's FocusHighlight at the row level — this view only
 *   draws the focus ring and starts the title marquee.
 */
class ItemCardView(context: Context) : LinearLayout(context) {
	companion object {
		private const val FALLBACK_ICON_SCALE = 0.4f
		private const val FOCUS_RING_WIDTH_DP = 2
		private const val CORNER_RADIUS_DP = 12

		// Tokens.Color.colorRed600 / colorGrey100 (record indicator tints)
		private const val COLOR_RECORDING_ACTIVE = 0xFFB9090F.toInt()
		private const val COLOR_RECORDING_INACTIVE = 0xFFEEEEEE.toInt()
	}

	private val binding = ViewCardItemBinding.inflate(LayoutInflater.from(context), this)

	/** Invoked from [onFocusChanged]; not overwritten by Leanback's FocusAnimator. */
	var focusCallback: ((Boolean) -> Unit)? = null

	/** Container the presenter attaches the focused-only Compose preview overlay into. */
	val previewContainer: ViewGroup get() = binding.cardPreviewContainer

	private val cornerRadius = CORNER_RADIUS_DP.dp(context).toFloat()
	private var circular = false
	private var focusRingColor = 0xFFFFFFFF.toInt()
	private var focusRing: GradientDrawable? = null
	private var cardWidth = 0
	private var cardHeight = 0
	private var fallbackFullSize = false

	init {
		orientation = VERTICAL
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			defaultFocusHighlightEnabled = false
		}

		binding.cardBox.clipToOutline = true
		binding.cardBox.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				if (circular) outline.setOval(0, 0, view.width, view.height)
				else outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
			}
		}
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
		applyFocusState(gainFocus)
		focusCallback?.invoke(gainFocus)
	}

	/** Applies the focus ring and marquee state. Safe to call again after (re)binding. */
	fun applyFocusState(focused: Boolean) {
		binding.cardBox.foreground = if (focused) getFocusRing() else null
		binding.cardFooterTitle.setMarqueeEnabled(focused)
		binding.cardMetaTitle.setMarqueeEnabled(focused)
		binding.cardMetaSubtitle.setMarqueeEnabled(focused)
	}

	fun setFocusRingColor(color: Int) {
		if (focusRingColor == color) return
		focusRingColor = color
		focusRing = null
		if (isFocused) binding.cardBox.foreground = getFocusRing()
	}

	fun setCircular(circular: Boolean) {
		if (this.circular == circular) return
		this.circular = circular
		focusRing = null
		binding.cardBox.invalidateOutline()
		if (isFocused) binding.cardBox.foreground = getFocusRing()
	}

	fun setCardSize(widthPx: Int, heightPx: Int) {
		if (cardWidth == widthPx && cardHeight == heightPx) return
		cardWidth = widthPx
		cardHeight = heightPx

		binding.cardBox.updateSize(widthPx, heightPx)
		// Metadata under the card is constrained to the card width, like ItemPreview did
		binding.cardMetaTitle.updateSize(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
		binding.cardMetaSubtitle.updateSize(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
		binding.cardServerBadge.maxWidth = (widthPx - 8.dp(context)).coerceAtLeast(0)
		updateFallbackSize()
	}

	/** Loads a remote image into the card via Coil (existing [org.jellyfin.androidtv.ui.AsyncImageView]). */
	fun loadImage(url: String?, blurHash: String?, aspectRatio: Float, scaleType: ImageView.ScaleType) {
		binding.cardFallbackImage.isVisible = false
		binding.cardFallbackImage.setImageDrawable(null)
		binding.cardImage.isVisible = true
		binding.cardImage.scaleType = scaleType
		binding.cardImage.load(
			url = url,
			blurHash = blurHash,
			aspectRatio = aspectRatio.toDouble(),
		)
	}

	/**
	 * Shows a local drawable instead of a remote image. [fullSize] renders it edge to edge
	 * (grid buttons); otherwise it is centered at 40% of the card size (fallback icons).
	 */
	fun showImageResource(@DrawableRes resource: Int, fullSize: Boolean) {
		binding.cardImage.clear()
		binding.cardImage.isVisible = false
		fallbackFullSize = fullSize
		updateFallbackSize()
		binding.cardFallbackImage.setImageResource(resource)
		binding.cardFallbackImage.isVisible = true
	}

	fun setFavorite(favorite: Boolean) {
		binding.cardFavoriteIndicator.isVisible = favorite
	}

	fun setRecording(recording: Boolean, active: Boolean) {
		binding.cardRecordIndicator.isVisible = recording
		if (recording) {
			binding.cardRecordIndicator.setColorFilter(
				if (active) COLOR_RECORDING_ACTIVE else COLOR_RECORDING_INACTIVE
			)
		}
	}

	fun setServerName(name: String?) {
		binding.cardServerBadge.isVisible = name != null
		binding.cardServerBadge.text = name
	}

	fun setWatchedState(played: Boolean, unplayedCount: Int?) {
		when {
			played -> {
				binding.cardWatchedBadge.isVisible = true
				binding.cardWatchedIcon.isVisible = true
				binding.cardUnwatchedCount.isVisible = false
			}

			unplayedCount != null -> {
				binding.cardWatchedBadge.isVisible = true
				binding.cardWatchedIcon.isVisible = false
				binding.cardUnwatchedCount.isVisible = true
				binding.cardUnwatchedCount.text = unplayedCount.toString()
			}

			else -> binding.cardWatchedBadge.isVisible = false
		}
	}

	/** Shows the playback progress bar for a fraction in (0, 1), hides it for null. */
	fun setProgress(progress: Float?) {
		binding.cardProgress.isVisible = progress != null
		if (progress != null) binding.cardProgress.progress = progress
	}

	/** In-card title footer (used when the card shows its info overlay instead of metadata below). */
	fun setFooterTitle(title: String?) {
		binding.cardFooterTitle.isVisible = title != null
		binding.cardFooterTitle.text = title
	}

	/** Title/subtitle metadata rendered under the card (previously the Compose ItemPreview). */
	fun setMetaText(title: String?, subtitle: String?) {
		binding.cardMetaTitle.isVisible = title != null
		binding.cardMetaTitle.text = title
		binding.cardMetaSubtitle.isVisible = subtitle != null
		binding.cardMetaSubtitle.text = subtitle
	}

	/**
	 * Fully resets the card for recycling — image, badges, progress, footer, metadata,
	 * preview overlay and focus visuals — so no state ghosts across rows.
	 */
	fun reset() {
		binding.cardImage.clear()
		binding.cardImage.isVisible = true
		binding.cardFallbackImage.setImageDrawable(null)
		binding.cardFallbackImage.isVisible = false
		setFavorite(false)
		setRecording(recording = false, active = false)
		setServerName(null)
		setWatchedState(played = false, unplayedCount = null)
		setProgress(null)
		setFooterTitle(null)
		setMetaText(null, null)
		previewContainer.removeAllViews()
		applyFocusState(false)
	}

	private fun getFocusRing(): GradientDrawable {
		focusRing?.let { return it }
		return GradientDrawable().apply {
			shape = if (circular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
			if (!circular) cornerRadius = this@ItemCardView.cornerRadius
			setColor(android.graphics.Color.TRANSPARENT)
			setStroke(FOCUS_RING_WIDTH_DP.dp(context), focusRingColor)
		}.also { focusRing = it }
	}

	private fun updateFallbackSize() {
		val width: Int
		val height: Int
		if (fallbackFullSize) {
			width = ViewGroup.LayoutParams.MATCH_PARENT
			height = ViewGroup.LayoutParams.MATCH_PARENT
		} else {
			width = (cardWidth * FALLBACK_ICON_SCALE).roundToInt()
			height = (cardHeight * FALLBACK_ICON_SCALE).roundToInt()
		}
		binding.cardFallbackImage.updateSize(width, height)
	}

	private fun View.updateSize(width: Int, height: Int) {
		val params = layoutParams
		if (params.width == width && params.height == height) return
		params.width = width
		params.height = height
		layoutParams = params
	}

	private fun TextView.setMarqueeEnabled(enabled: Boolean) {
		// Classic marquee: only runs while selected; ellipsize with "…" when idle
		ellipsize = if (enabled) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
		isSelected = enabled
	}
}

/**
 * Minimal playback progress bar for [ItemCardView]: a rounded background track with a rounded
 * fill, matching the Compose Seekbar rendering (no knob, non-interactive).
 */
class CardProgressBarView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
	companion object {
		// JellyfinTheme colorScheme.rangeControlBackground / rangeControlFill
		private const val COLOR_TRACK = 0xFF474A52.toInt()
		private const val COLOR_FILL = 0xFF00A4DD.toInt()
	}

	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TRACK }
	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_FILL }

	var progress: Float = 0f
		set(value) {
			val coerced = value.coerceIn(0f, 1f)
			if (field == coerced) return
			field = coerced
			invalidate()
		}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val width = width.toFloat()
		val height = height.toFloat()
		val radius = height / 2f

		canvas.drawRoundRect(0f, 0f, width, height, radius, radius, trackPaint)
		if (progress > 0f) {
			canvas.drawRoundRect(0f, 0f, width * progress, height, radius, radius, fillPaint)
		}
	}
}
