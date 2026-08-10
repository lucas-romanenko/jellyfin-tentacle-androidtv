package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.card.ItemCardView
import org.jellyfin.androidtv.ui.composable.item.EpisodePreviewOverlay
import org.jellyfin.androidtv.ui.composable.item.SeriesTrailerOverlay
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
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Leanback presenter for the standard poster card.
 *
 * The card itself is a plain Android View ([ItemCardView]) — no per-card Compose. Composition
 * and bind cost of a ComposeView per card dominated frame time during row scrolling on weak TV
 * GPUs (MediaTek-class), capping scroll at ~30fps. The only remaining Compose usage is the
 * episode/trailer video preview overlay, which is lazily attached to a card only while it is
 * focused (see [attachPreviewOverlay]) and disposed when focus leaves.
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

	companion object {
		/** Sentinel height: callers passing 150 get the user-configurable poster size. */
		private const val DEFAULT_STATIC_HEIGHT = 150

		/** Landscape cards use a shorter height so they don't dominate rows of portrait posters. */
		private const val LANDSCAPE_HEIGHT_RATIO = 0.73f

		private const val NON_STATIC_LANDSCAPE_HEIGHT = 130
		private const val NON_STATIC_PORTRAIT_HEIGHT = 150

		private const val MIN_VALID_ASPECT_RATIO = 0.1f
	}

	private val api by inject<ApiClient>()
	private val apiClientFactory by inject<ApiClientFactory>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userRepository by inject<UserRepository>()
	private val serverRepository by inject<ServerRepository>()

	// Focus ring color, cached per user (mirrors focusBorderColor() semantics)
	private var focusColorUserId: UUID? = null
	private var focusColorValue: Int? = null

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder =
		CardViewHolder(ItemCardView(parent.context))

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return
		if (item !is BaseRowItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	private fun getFocusRingColor(context: Context): Int {
		val userId = userRepository.currentUser.value?.id
		val cached = focusColorValue
		if (cached != null && userId == focusColorUserId) return cached

		val color = UserSettingPreferences(context, userId)[UserSettingPreferences.focusColor].colorValue.toInt()
		focusColorUserId = userId
		focusColorValue = color
		return color
	}

	private fun resolveApiClient(item: BaseRowItem): ApiClient {
		val baseItem = item.baseItem
		return when {
			baseItem != null -> apiClientFactory.getApiClientForItemOrFallback(baseItem, api)
			item is ChapterItemInfoBaseRowItem && item.serverId != null -> {
				val serverUuid = UUIDUtils.parseUUID(item.serverId)
				if (serverUuid != null) apiClientFactory.getApiClientForServer(serverUuid) ?: api else api
			}

			else -> api
		}
	}

	/**
	 * Attaches a ComposeView hosting the episode/trailer preview overlay. Only called while the
	 * card is focused and the item is eligible — the regular bind path never touches Compose.
	 */
	private fun attachPreviewOverlay(cardView: ItemCardView, item: BaseRowItem) {
		val baseItem = item.baseItem ?: return
		val episodePreview = userSettingPreferences[UserSettingPreferences.episodePreviewEnabled] &&
			isEligibleForPreview(baseItem)
		val trailerPreview = userSettingPreferences[UserSettingPreferences.mediaBarTrailerPreview] &&
			isEligibleForTrailerPreview(baseItem)
		if (!episodePreview && !trailerPreview) return

		val muted = !userSettingPreferences[UserSettingPreferences.previewAudioEnabled]
		val composeView = ComposeView(cardView.context).apply {
			isFocusable = false
			setParentCompositionContext(cardView.findViewTreeCompositionContext())
			// Default view composition strategy disposes on detach, releasing the player
			// as soon as the overlay is removed on focus loss/unbind.
			setContent {
				if (episodePreview) {
					EpisodePreviewOverlay(
						item = baseItem,
						focused = true,
						muted = muted,
					)
				}
				if (trailerPreview) {
					SeriesTrailerOverlay(
						item = baseItem,
						focused = true,
						muted = muted,
					)
				}
			}
		}

		cardView.previewContainer.addView(
			composeView,
			FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			),
		)
	}

	private inner class CardViewHolder(
		private val cardView: ItemCardView,
	) : ViewHolder(cardView) {
		private var boundItem: BaseRowItem? = null

		init {
			cardView.focusCallback = { focused ->
				// The ring + marquee are handled inside ItemCardView; the presenter only
				// manages the focused-only Compose preview overlay.
				cardView.previewContainer.removeAllViews()
				if (focused) boundItem?.let { attachPreviewOverlay(cardView, it) }
			}
		}

		fun bind(item: BaseRowItem) {
			boundItem = item
			val context = cardView.context

			val displayConfig = item.getDisplayConfig(imageType, uniformAspect)
			val image = displayConfig.image
			val aspectRatio = displayConfig.aspectRatio.takeIf { it >= MIN_VALID_ASPECT_RATIO }
				?: image?.aspectRatio?.takeIf { it >= MIN_VALID_ASPECT_RATIO } ?: 1f

			// Card size (dp), mirroring the previous Compose sizing logic
			val posterSize = userPreferences[UserPreferences.posterSize]
			val effectiveStaticHeight = if (staticHeight == DEFAULT_STATIC_HEIGHT) posterSize.height else staticHeight
			val effectiveLandscapeHeight = if (staticHeight == DEFAULT_STATIC_HEIGHT) {
				posterSize.landscapeHeight
			} else {
				(staticHeight * LANDSCAPE_HEIGHT_RATIO).toInt()
			}

			val heightDp = when {
				item.staticHeight && aspectRatio > 1f -> effectiveLandscapeHeight.toFloat()
				item.staticHeight -> effectiveStaticHeight.toFloat()
				aspectRatio > 1f -> NON_STATIC_LANDSCAPE_HEIGHT.toFloat()
				else -> NON_STATIC_PORTRAIT_HEIGHT.toFloat()
			}
			val widthDp = heightDp * aspectRatio

			val density = context.resources.displayMetrics.density
			val widthPx = (widthDp * density).roundToInt()
			val heightPx = (heightDp * density).roundToInt()

			cardView.setFocusRingColor(getFocusRingColor(context))
			cardView.setCircular(displayConfig.isCircular)
			cardView.setCardSize(widthPx, heightPx)

			// Image
			val gridButtonImageRes = (item as? GridButtonBaseRowItem)?.gridButton?.imageRes
			when {
				image != null -> cardView.loadImage(
					url = image.getUrl(
						resolveApiClient(item),
						maxWidth = widthPx,
						maxHeight = heightPx,
					),
					blurHash = image.blurHash,
					aspectRatio = aspectRatio,
					scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP,
				)

				gridButtonImageRes != null -> cardView.showImageResource(gridButtonImageRes, fullSize = true)

				else -> cardView.showImageResource(displayConfig.iconRes, fullSize = false)
			}

			val usePreview = displayConfig.overrideShowInfo ?: showInfo
			val title = item.getCardName(context)

			// Overlay badges/progress (only for items backed by a BaseItemDto, as before)
			val baseItem = item.baseItem
			if (baseItem != null) {
				bindOverlay(baseItem, usePreview, item.showCardInfoOverlay, title)
			} else {
				clearOverlay()
			}

			// Metadata below the card (previously the Compose ItemPreview path)
			if (usePreview) {
				cardView.setMetaText(title, item.getSubText(context))
			} else {
				cardView.setMetaText(null, null)
			}

			// Recycled cards can be (re)bound while focused — re-apply focus visuals and
			// restart the preview overlay for the new item
			cardView.applyFocusState(cardView.isFocused)
			cardView.previewContainer.removeAllViews()
			if (cardView.isFocused) attachPreviewOverlay(cardView, item)
		}

		fun unbind() {
			boundItem = null
			cardView.reset()
		}

		private fun bindOverlay(baseItem: BaseItemDto, usePreview: Boolean, showCardInfoOverlay: Boolean, title: String?) {
			// Favorite + recording state indicators
			val isRecording = baseItem.timerId?.takeIf {
				baseItem.type == BaseItemKind.LIVE_TV_PROGRAM || baseItem.type == BaseItemKind.PROGRAM
			} != null
			val isRecordingActive = baseItem.seriesTimerId != null && isRecording
			cardView.setFavorite(baseItem.userData?.isFavorite == true)
			cardView.setRecording(isRecording, isRecordingActive)

			// Server badge (multi-server mode)
			val serverName = if (showServerBadge && userPreferences[UserPreferences.enableMultiServerLibraries]) {
				baseItem.serverId?.toUUIDOrNull()?.let { serverUuid ->
					serverRepository.storedServers.value.find { it.id == serverUuid }?.name
				}
			} else {
				null
			}
			cardView.setServerName(serverName)

			// Watched indicator / unplayed count badge
			val watchedBehavior = userPreferences[UserPreferences.watchedIndicatorBehavior]
			val showWatched = watchedBehavior != WatchedIndicatorBehavior.NEVER &&
				!(watchedBehavior == WatchedIndicatorBehavior.EPISODES_ONLY && baseItem.type != BaseItemKind.EPISODE)
			val isPlayed = baseItem.userData?.played == true
			val unplayedItems = baseItem.userData?.unplayedItemCount?.takeIf { it > 0 }
			when {
				!showWatched -> cardView.setWatchedState(played = false, unplayedCount = null)
				isPlayed -> cardView.setWatchedState(played = true, unplayedCount = null)
				unplayedItems != null && watchedBehavior != WatchedIndicatorBehavior.HIDE_UNWATCHED ->
					cardView.setWatchedState(played = false, unplayedCount = unplayedItems)

				else -> cardView.setWatchedState(played = false, unplayedCount = null)
			}

			// Playback progress bar
			@Suppress("MagicNumber")
			val progress = baseItem.userData?.playedPercentage?.toFloat()?.div(100f)
				?.coerceIn(0f, 1f)?.takeIf { it > 0f && it < 1f }
			cardView.setProgress(progress)

			// In-card title footer
			val showFooter = !usePreview && showCardInfoOverlay && title != null
			cardView.setFooterTitle(if (showFooter) title else null)
		}

		private fun clearOverlay() {
			cardView.setFavorite(false)
			cardView.setRecording(recording = false, active = false)
			cardView.setServerName(null)
			cardView.setWatchedState(played = false, unplayedCount = null)
			cardView.setProgress(null)
			cardView.setFooterTitle(null)
		}
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

		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = getImage(imageType),
			iconRes = R.drawable.ic_clapperboard,
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
