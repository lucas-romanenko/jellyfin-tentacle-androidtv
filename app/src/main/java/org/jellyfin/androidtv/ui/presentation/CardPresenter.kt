package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.OneShotPreDrawListener
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.preference.FixedLook
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.AsyncImageView
import org.jellyfin.androidtv.ui.composable.item.EpisodePreviewOverlay
import org.jellyfin.androidtv.ui.composable.item.SeriesTrailerOverlay
import org.jellyfin.androidtv.ui.composable.item.hasLocalMedia
import org.jellyfin.androidtv.ui.composable.item.isEligibleForPreview
import org.jellyfin.androidtv.ui.composable.item.isEligibleForTrailerPreview
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.ChapterItemInfoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.UUIDUtils
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.androidtv.util.sdk.isLiveTvRecording
import org.jellyfin.design.Tokens
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queueOrNull
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.roundToInt

/**
 * FrameLayout wrapper that provides a reliable focus callback via [onFocusChanged] override.
 *
 * Leanback's `FocusHighlightHelper` sets its own [android.view.View.OnFocusChangeListener] on
 * item views during binding (via `FocusAnimator`), overwriting any listener set by the
 * Presenter. The [onFocusChanged] override is a protected [android.view.View] method that
 * always fires regardless, so our focus state tracking works.
 */
private class FocusAwareCardContainer(context: Context) : FrameLayout(context) {
	/** Callback invoked when focus state changes. Not overwritten by Leanback's FocusAnimator. */
	var focusCallback: ((Boolean) -> Unit)? = null

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			defaultFocusHighlightEnabled = false
		}
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
		focusCallback?.invoke(gainFocus)
	}
}

/** The thin watch-progress bar at the bottom of a card (the look of the disabled Seekbar). */
private class CardProgressView(context: Context) : View(context) {
	private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.Color.colorBluegrey700.toArgb() }
	private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.Color.colorCyan500.toArgb() }

	var fraction = 0f
		set(value) {
			if (field == value) return
			field = value
			invalidate()
		}

	override fun onDraw(canvas: Canvas) {
		val w = width.toFloat()
		val h = height.toFloat()
		val r = minOf(w, h)
		canvas.drawRoundRect(0f, 0f, w, h, r, r, track)
		if (fraction > 0f) canvas.drawRoundRect(0f, 0f, w * fraction, h, r, r, fill)
	}
}

/**
 * Cards for every Leanback row and grid: plain Android views.
 *
 * Each card used to be its own ComposeView, so every card had its own AndroidComposeView with
 * its own snapshot observer, lifecycle observers and flow collectors. Scrolling Home down
 * detaches and attaches whole rows of them, and each detach unregisters from Compose's one
 * global apply-observer list under a lock; with compositions kept alive for pooled (and
 * discarded) cards the main thread spent seconds in that and the Sony TV ended in an ANR
 * (2026-10-21). Views cost a fraction of that to bind, attach and detach. Compose is only
 * used for the focused card's video preview, created once focus rests on it.
 */
class CardPresenter @JvmOverloads constructor(
	val showInfo: Boolean,
	val imageType: ImageType,
	val staticHeight: Int,
	val uniformAspect: Boolean,
	val showServerBadge: Boolean = false,
) : Presenter(), KoinComponent {
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int) : this(showInfo, imageType, staticHeight, false)
	constructor(showInfo: Boolean, staticHeight: Int) : this(showInfo, ImageType.POSTER, staticHeight)
	constructor(showInfo: Boolean) : this(showInfo, 150)
	constructor() : this(true)

	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val serverRepository by inject<ServerRepository>()
	private val tentacleRepository by inject<TentacleRepository>()
	private val playbackManager by inject<PlaybackManager>()
	private val apiClientFactory by inject<ApiClientFactory>()
	private val api by inject<ApiClient>()

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val container = FocusAwareCardContainer(parent.context)
		// The preview's ComposeView needs these; set them up front like the cards always did.
		container.setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
		container.setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
		return CardViewHolder(container)
	}

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return
		if (item !is BaseRowItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	/**
	 * The picture a card of this presenter loads for [item], the URL [CardViewHolder.bind] asks
	 * for: a row changing shape fetches its new cards' pictures before it goes up.
	 */
	fun imageUrl(context: Context, item: BaseRowItem): String? {
		val displayConfig = item.getDisplayConfig(imageType, uniformAspect)
		val jellyfinImage = displayConfig.image ?: return null
		val (widthPx, heightPx) = cardSizePx(item, cardAspectRatio(displayConfig), context.resources.displayMetrics.density)
		return jellyfinImage.getUrl(resolveApi(item), maxWidth = widthPx, maxHeight = heightPx)
	}

	private fun cardAspectRatio(displayConfig: BaseRowItemDisplayConfig): Float =
		displayConfig.aspectRatio.takeIf { it >= 0.1f }
			?: displayConfig.image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

	/** A card's image size in pixels. */
	private fun cardSizePx(item: BaseRowItem, aspectRatio: Float, density: Float): Pair<Int, Int> {
		val posterSize = userPreferences[UserPreferences.posterSize]
		val size = cardSizeDp(aspectRatio, item.staticHeight, staticHeight, posterSize.height, posterSize.landscapeHeight)
		return (size.width * density).roundToInt() to (size.height * density).roundToInt()
	}

	private fun resolveApi(item: BaseRowItem): ApiClient = when {
		item.baseItem != null -> apiClientFactory.getApiClientForItemOrFallback(item.baseItem, api)
		item is ChapterItemInfoBaseRowItem && item.serverId != null -> {
			val serverUuid = UUIDUtils.parseUUID(item.serverId)
			if (serverUuid != null) apiClientFactory.getApiClientForServer(serverUuid) ?: api else api
		}
		else -> api
	}

	private inner class CardViewHolder(
		private val container: FocusAwareCardContainer,
	) : ViewHolder(container) {
		private val context = container.context
		private val density = context.resources.displayMetrics.density
		private fun dp(value: Float) = (value * density).roundToInt()
		private fun dp(value: Int) = dp(value.toFloat())

		private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
		private val frame = FrameLayout(context).apply {
			background = ColorDrawable(Tokens.Color.colorBluegrey900.toArgb())
			clipToOutline = true
		}
		private val image = AsyncImageView(context)
		private val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
		private val previewHost = FrameLayout(context)
		private val overlay = FrameLayout(context).apply { setPadding(dp(4), dp(4), dp(4), dp(4)) }
		private val stateColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
		private val recordIcon = ImageView(context).apply { setImageResource(R.drawable.ic_record_series) }
		private val favoriteIcon = ImageView(context).apply {
			setImageResource(R.drawable.ic_heart)
			imageTintList = ColorStateList.valueOf(Tokens.Color.colorRed500.toArgb())
		}
		private val serverBadge = TextView(context).apply {
			setTextColor(android.graphics.Color.WHITE)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
			maxLines = 1
			ellipsize = TextUtils.TruncateAt.END
			setPadding(dp(4), dp(2), dp(4), dp(2))
			background = GradientDrawable().apply {
				setColor(android.graphics.Color.argb(178, 0, 0, 0))
				cornerRadius = dp(4).toFloat()
			}
		}
		private val watchBadgeView = FrameLayout(context).apply {
			minimumWidth = dp(24)
			minimumHeight = dp(24)
			setPadding(dp(4), dp(4), dp(4), dp(4))
			background = GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(Tokens.Color.colorCyan500.toArgb())
			}
		}
		private val watchIcon = ImageView(context).apply {
			setImageResource(R.drawable.ic_watch)
			imageTintList = ColorStateList.valueOf(Tokens.Color.colorBluegrey100.toArgb())
		}
		private val watchCount = TextView(context).apply {
			setTextColor(Tokens.Color.colorBluegrey100.toArgb())
			setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
			typeface = Typeface.DEFAULT_BOLD
			gravity = Gravity.CENTER
			includeFontPadding = false
		}
		private val bottomColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
		private val progress = CardProgressView(context)
		private val footer = TextView(context).apply {
			setTextColor(android.graphics.Color.WHITE)
			gravity = Gravity.CENTER
			setSingleLine(true)
			ellipsize = TextUtils.TruncateAt.END
			marqueeRepeatLimit = 3
			setPadding(dp(4), dp(4), dp(4), dp(4))
			background = GradientDrawable().apply {
				setColor(Tokens.Color.colorBluegrey900.copy(alpha = 0.6f).toArgb())
				cornerRadius = dp(4).toFloat()
			}
		}
		private val metadata = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(dp(4), dp(4), dp(4), dp(4))
		}
		private val title = metadataText(12f, Tokens.Color.colorGrey100.toArgb())
		private val subtitle = metadataText(10f, Tokens.Color.colorGrey300.toArgb())
		private val focusBorder = GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) }

		private var item: BaseRowItem? = null
		private var boundUrl: String? = null
		private var isCircular = false
		private var preview: ComposeView? = null
		private val startPreview = Runnable { showPreview() }

		init {
			frame.outlineProvider = object : ViewOutlineProvider() {
				override fun getOutline(view: View, outline: Outline) {
					if (isCircular) outline.setOval(0, 0, view.width, view.height)
					else outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
				}
			}
			frame.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
			frame.addView(icon, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
			frame.addView(previewHost, FrameLayout.LayoutParams(MATCH, MATCH))

			stateColumn.addView(recordIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
			stateColumn.addView(favoriteIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
			overlay.addView(stateColumn, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
			overlay.addView(serverBadge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
			watchBadgeView.addView(watchIcon, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
			watchBadgeView.addView(watchCount, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
			overlay.addView(watchBadgeView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
			bottomColumn.addView(progress, LinearLayout.LayoutParams(MATCH, dp(4)).apply {
				setMargins(dp(4), dp(4), dp(4), dp(4))
			})
			bottomColumn.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
			overlay.addView(bottomColumn, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
			frame.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

			metadata.addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
			metadata.addView(subtitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
			column.addView(frame, LinearLayout.LayoutParams(dp(100), dp(150)))
			column.addView(metadata, LinearLayout.LayoutParams(dp(100), WRAP).apply { topMargin = dp(4) })
			container.addView(column, FrameLayout.LayoutParams(WRAP, WRAP))

			// Use onFocusChanged callback on the container instead of OnFocusChangeListener
			// because Leanback's FocusHighlightHelper.BrowseItemFocusHighlight overwrites
			// OnFocusChangeListener with its own FocusAnimator during item binding.
			container.focusCallback = { focused -> onFocus(focused) }
		}

		private fun metadataText(sizeSp: Float, color: Int) = TextView(context).apply {
			setTextColor(color)
			setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
			gravity = Gravity.CENTER_HORIZONTAL
			setSingleLine(true)
			ellipsize = TextUtils.TruncateAt.END
			marqueeRepeatLimit = 3
		}

		fun bind(item: BaseRowItem) {
			this.item = item
			val displayConfig = item.getDisplayConfig(imageType, uniformAspect)
			val jellyfinImage = displayConfig.image
			val aspectRatio = cardAspectRatio(displayConfig)
			val (widthPx, heightPx) = cardSizePx(item, aspectRatio, density)

			frame.layoutParams = frame.layoutParams.apply {
				width = widthPx
				height = heightPx
			}
			if (isCircular != displayConfig.isCircular) {
				isCircular = displayConfig.isCircular
				frame.invalidateOutline()
			}

			bindImage(item, displayConfig, jellyfinImage, aspectRatio, widthPx, heightPx)

			val cardTitle = item.getCardName(context)
			val usePreview = displayConfig.overrideShowInfo ?: showInfo
			val baseItem = item.baseItem
			bindOverlay(baseItem, if (!usePreview && item.showCardInfoOverlay) cardTitle else null)

			metadata.visibility = if (usePreview) View.VISIBLE else View.GONE
			if (usePreview) {
				metadata.layoutParams = metadata.layoutParams.apply { width = widthPx }
				title.setTextOrGone(cardTitle)
				subtitle.setTextOrGone(item.getSubText(context))
			}

			applyFocus(container.isFocused)
		}

		private fun bindImage(
			item: BaseRowItem,
			displayConfig: BaseRowItemDisplayConfig,
			jellyfinImage: JellyfinImage?,
			aspectRatio: Float,
			widthPx: Int,
			heightPx: Int,
		) {
			if (jellyfinImage != null) {
				icon.visibility = View.GONE
				image.visibility = View.VISIBLE
				image.scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP
				val url = jellyfinImage.getUrl(resolveApi(item), maxWidth = widthPx, maxHeight = heightPx)
				// The same poster again (a row refresh rebinding its cards): keep what's on screen.
				if (url == boundUrl && image.drawable != null) return
				// Another picture on a card bound again without an unbind (a row refresh moving
				// items, a row turning wide): drop the old one, or it showed until the new one came.
				if (boundUrl != null) image.clear()
				boundUrl = url
				// A poster fitted on a wide card: its placeholder takes the poster's shape too.
				val fitted = imageType == ImageType.THUMB && displayConfig.scaleType == ImageView.ScaleType.FIT_CENTER
				image.load(
					url = url,
					blurHash = jellyfinImage.blurHash,
					aspectRatio = ((if (fitted) jellyfinImage.aspectRatio else null) ?: aspectRatio).toDouble(),
				)
				return
			}

			boundUrl = null
			image.clear()
			image.visibility = View.GONE
			icon.visibility = View.VISIBLE
			val gridImage = (item as? GridButtonBaseRowItem)?.gridButton?.imageRes
			icon.setImageResource(gridImage ?: displayConfig.iconRes)
			icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).apply {
				// Grid buttons fill the card; a type icon sits in the middle at 40%.
				width = if (gridImage != null) MATCH else (widthPx * 0.4f).roundToInt()
				height = if (gridImage != null) MATCH else (heightPx * 0.4f).roundToInt()
			}
		}

		private fun bindOverlay(baseItem: BaseItemDto?, footerTitle: String?) {
			if (baseItem == null) {
				overlay.visibility = View.GONE
				return
			}
			overlay.visibility = View.VISIBLE

			val isRecording = baseItem.timerId != null &&
				(baseItem.type == BaseItemKind.LIVE_TV_PROGRAM || baseItem.type == BaseItemKind.PROGRAM)
			recordIcon.visibility = if (isRecording) View.VISIBLE else View.GONE
			if (isRecording) {
				val active = baseItem.seriesTimerId != null
				recordIcon.imageTintList = ColorStateList.valueOf(
					(if (active) Tokens.Color.colorRed600 else Tokens.Color.colorGrey100).toArgb()
				)
			}
			favoriteIcon.visibility = if (baseItem.userData?.isFavorite == true) View.VISIBLE else View.GONE
			favoriteIcon.layoutParams = (favoriteIcon.layoutParams as LinearLayout.LayoutParams).apply {
				topMargin = if (isRecording) dp(4) else 0
			}

			val serverName = if (showServerBadge) serverName(baseItem) else null
			serverBadge.setTextOrGone(serverName)

			when (val badge = watchBadge(
				behavior = userPreferences[UserPreferences.watchedIndicatorBehavior],
				type = baseItem.type,
				played = baseItem.userData?.played == true,
				unplayedCount = baseItem.userData?.unplayedItemCount,
			)) {
				WatchBadge.None -> watchBadgeView.visibility = View.GONE
				WatchBadge.Played -> {
					watchBadgeView.visibility = View.VISIBLE
					watchIcon.visibility = View.VISIBLE
					watchCount.visibility = View.GONE
				}
				is WatchBadge.Unplayed -> {
					watchBadgeView.visibility = View.VISIBLE
					watchIcon.visibility = View.GONE
					watchCount.visibility = View.VISIBLE
					watchCount.text = badge.count.toString()
				}
			}

			val fraction = playingFraction(baseItem) ?: playedFraction(baseItem.userData?.playedPercentage)
			progress.visibility = if (fraction != null) View.VISIBLE else View.GONE
			progress.fraction = fraction ?: 0f

			footer.setTextOrGone(footerTitle)
		}

		/** The live position when this item is the one playing (background audio, say). */
		private fun playingFraction(baseItem: BaseItemDto): Float? {
			if (playbackManager.state.playState.value != PlayState.PLAYING) return null
			if (playbackManager.queueOrNull?.entry?.value?.baseItem?.id != baseItem.id) return null
			val position = playbackManager.state.positionInfo
			val durationMs = position.duration.inWholeMilliseconds
			if (durationMs <= 0) return null
			return (position.active.inWholeMilliseconds.toFloat() / durationMs).coerceIn(0f, 1f)
		}

		private fun serverName(baseItem: BaseItemDto): String? {
			if (!userPreferences[UserPreferences.enableMultiServerLibraries]) return null
			val serverUuid = baseItem.serverId?.toUUIDOrNull() ?: return null
			return serverRepository.storedServers.value.find { it.id == serverUuid }?.name
		}

		fun unbind() {
			cancelPreview()
			item = null
			boundUrl = null
			image.clear()
		}

		private fun onFocus(focused: Boolean) {
			applyFocus(focused)
			if (focused) {
				container.removeCallbacks(startPreview)
				if (wantsPreview()) container.postDelayed(startPreview, PREVIEW_VIEW_DELAY_MS)
			} else {
				cancelPreview()
			}
		}

		private fun applyFocus(focused: Boolean) {
			if (focused) {
				focusBorder.setStroke(dp(2), FixedLook.FOCUS_COLOR.toArgb())
				focusBorder.cornerRadius = if (isCircular) 0f else dp(12).toFloat()
				focusBorder.shape = if (isCircular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
				frame.foreground = focusBorder
			} else {
				frame.foreground = null
			}
			// Finite marquee on the focused card only: an endless one redraws every frame.
			// Unselect first, while the text is still a marquee: only then does TextView stop the
			// marquee and rewind it. Switched to END first, the marquee was never stopped and kept
			// its offset, and the next focus showed the title cut at the start ("erial Killer
			// Connec"). This also restarts it from the first letter when a focused card is bound again.
			val ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
			val switched = title.ellipsize != ellipsize
			for (text in arrayOf(title, subtitle, footer)) {
				text.isSelected = false
				text.ellipsize = ellipsize
				text.isSelected = focused
			}
			// Switched in the middle of a layout (a card bound again and focused while its row is
			// rebuilt for a new shape), the text kept the scroll of the other ellipsize and showed
			// cut at both ends ("erial Killer Conne"): from the first letter once laid out, and so is a
			// focused card bound again.
			if (switched || focused) OneShotPreDrawListener.add(container) {
				for (text in arrayOf(title, subtitle, footer)) text.scrollToTextStart()
			}
		}

		private fun wantsPreview(): Boolean {
			val baseItem = item?.baseItem ?: return false
			return episodePreview(baseItem) || trailerPreview(baseItem)
		}

		private fun episodePreview(baseItem: BaseItemDto) =
			userSettingPreferences[UserSettingPreferences.episodePreviewEnabled] &&
				isEligibleForPreview(baseItem) &&
				previewAllowedByServer(tentacleRepository.cardPreviewPolicy.value, baseItem.hasLocalMedia())

		private fun trailerPreview(baseItem: BaseItemDto) =
			userSettingPreferences[UserSettingPreferences.mediaBarTrailerPreview] &&
				isEligibleForTrailerPreview(baseItem)

		/** The focused card's preview, the only Compose in a card (H6 still waits for focus to rest). */
		private fun showPreview() {
			val baseItem = item?.baseItem ?: return
			if (preview != null || !container.isFocused) return
			val episode = episodePreview(baseItem)
			val trailer = trailerPreview(baseItem)
			if (!episode && !trailer) return
			preview = ComposeView(context).apply {
				isFocusable = false
				setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
				setContent {
					// Card previews are always muted (H6); the hero's trailer sound is its own.
					if (episode) EpisodePreviewOverlay(item = baseItem, focused = true)
					if (trailer) SeriesTrailerOverlay(item = baseItem, focused = true)
				}
			}.also { previewHost.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
		}

		private fun cancelPreview() {
			container.removeCallbacks(startPreview)
			val view = preview ?: return
			preview = null
			view.disposeComposition()
			previewHost.removeView(view)
		}
	}

	private companion object {
		const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
		const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

		/**
		 * Focus rests this long before the preview's ComposeView is even built, so D-pad
		 * browsing builds none; the preview itself then waits for its own rest (PREVIEW_REST_MS).
		 */
		const val PREVIEW_VIEW_DELAY_MS = 500L
	}
}

private fun TextView.setTextOrGone(value: String?) {
	if (value.isNullOrEmpty()) {
		visibility = View.GONE
	} else {
		visibility = View.VISIBLE
		if (text?.toString() != value) text = value
	}
}

private data class BaseRowItemDisplayConfig(
	val image: JellyfinImage?,
	val iconRes: Int,
	val aspectRatio: Float,
	val overrideShowInfo: Boolean? = null,
	val scaleType: ImageView.ScaleType? = null,
	val isCircular: Boolean = false,
)

private fun BaseRowItem.getDisplayConfig(imageType: ImageType, uniformAspect: Boolean): BaseRowItemDisplayConfig = when (baseRowType) {
	BaseRowType.BaseItem -> {
		val preferSeriesPoster = this is BaseItemDtoBaseRowItem && preferSeriesPoster
		val primaryAspectRatio = baseItem?.primaryImageAspectRatio?.toFloat()
		val defaultAspectRatio = when {
			preferParentThumb && (baseItem?.parentThumbItemId != null || baseItem?.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.EPISODE && primaryAspectRatio != null -> primaryAspectRatio
			baseItem?.type == BaseItemKind.EPISODE && (baseItem.parentThumbItemId != null || baseItem.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.USER_VIEW -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> primaryAspectRatio ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		}

		val image = getImage(imageType)
		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = image,
			iconRes = R.drawable.ic_clapperboard,
			// A poster on a wide card is shown whole on the card background, not cropped to 16:9.
			scaleType = if (imageType == ImageType.THUMB && image != null && !fillsWideCard(image)) ImageView.ScaleType.FIT_CENTER else null,
		)

		when (baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> base.copy(
				iconRes = R.drawable.ic_music_album,
				aspectRatio = 1f,
			)

			BaseItemKind.PERSON -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = 1f,
				isCircular = true,
			)

			BaseItemKind.MUSIC_ARTIST -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = 1f,
			)

			BaseItemKind.SEASON, BaseItemKind.SERIES -> base.copy(
				aspectRatio = if (imageType == ImageType.POSTER) ImageHelper.ASPECT_RATIO_2_3.toFloat() else base.aspectRatio,
				iconRes = R.drawable.ic_tv
			)

			BaseItemKind.EPISODE -> base.copy(
				aspectRatio = when {
					preferSeriesPoster -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					else -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				},
				iconRes = R.drawable.ic_tv,
			)

			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> base.copy(
				aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE -> base.copy(
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.PHOTO -> base.copy(
				iconRes = R.drawable.ic_photo
			)

			BaseItemKind.PHOTO_ALBUM, BaseItemKind.PLAYLIST -> base.copy(
				iconRes = R.drawable.ic_folder
			)

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> base.copy(
				aspectRatio = when (imageType) {
					ImageType.POSTER -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					else -> base.aspectRatio
				},
				iconRes = R.drawable.ic_clapperboard,
				// A recording without its poster: its 16:9 picture whole on the poster card, not cropped
				scaleType = if (imageType == ImageType.POSTER && image != null && baseItem?.isLiveTvRecording == true &&
					isWideForPosterCard(image)) ImageView.ScaleType.FIT_CENTER else base.scaleType,
			)

			else -> base
		}
	}

	BaseRowType.LiveTvChannel -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: 1f
		},
		image = getImage(imageType),
		scaleType = ImageView.ScaleType.FIT_CENTER,
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.LiveTvProgram -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
		overrideShowInfo = true,
	)

	BaseRowType.LiveTvRecording -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.SeriesTimer -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		iconRes = R.drawable.ic_tv_timer,
		image = getImage(imageType),
		overrideShowInfo = true,
	)

	BaseRowType.Person -> BaseRowItemDisplayConfig(
		aspectRatio = 1f,
		image = getImage(imageType),
		iconRes = R.drawable.ic_user,
		isCircular = true,
	)

	BaseRowType.Chapter -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)

	BaseRowType.GridButton -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)
}
