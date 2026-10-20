package org.jellyfin.androidtv.data.service

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.util.BitmapBlur
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.UUID
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class BlurContext {
	DETAILS,
	BROWSING,
	NONE
}

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val userSettingPreferences: UserSettingPreferences,
	private val imageLoader: ImageLoader,
	private val apiClientFactory: ApiClientFactory,
) {
	companion object {
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Current background data
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurContext = MutableStateFlow(BlurContext.NONE)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurContext get() = _blurContext.asStateFlow()
	val enabled get() = _enabled.asStateFlow()
	
	/**
	 * Returns true if blur should be applied via Compose modifier (Android 12+, outside Home and
	 * the other browsing screens), false if blur is pre-applied to the bitmap. While browsing the
	 * background changes with every card, and a fullscreen GPU blur redrawn through each
	 * crossfade costs weak TV chips frames: there the small bitmap is blurred once (H5).
	 */
	fun usesComposeBlur(blurContext: BlurContext): Boolean =
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurContext != BlurContext.BROWSING

	// Background art is drawn dimmed and blurred behind everything: 960 px wide is plenty, 480
	// on a low-memory TV. Without a size Jellyfin sent the original, often 4K, for every card
	// focused while browsing (H5).
	private val backdropMaxWidth by lazy {
		if (context.getSystemService<ActivityManager>()?.isLowRamDevice == true) 480 else 960
	}

	/**
	 * Use all available backdrops from [baseItem] as background.
	 * @param blurContext The context to determine which blur amount preference to use
	 */
	@JvmOverloads
	fun setBackground(baseItem: BaseItemDto?, blurContext: BlurContext = BlurContext.DETAILS) {
		// Check if item is set and backgrounds are enabled
		if (baseItem == null || !userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Set blur context
		_blurContext.value = blurContext

		// Get the appropriate API client for this item's server
		val itemApi = apiClientFactory.getApiClientForItemOrFallback(baseItem, api)
		
		// Get all backdrop urls; while browsing only the first (each focused card loaded all of
		// them for a slideshow nobody waits 30 s for)
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.let { if (blurContext == BlurContext.BROWSING) it.take(1) else it }
			.map { it.getUrl(itemApi, maxWidth = backdropMaxWidth) }
			.toSet()

		loadBackgrounds(backdropUrls)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// No blur on splashscreen
		_blurContext.value = BlurContext.NONE

		// Manually grab the backdrop URL
		val api = jellyfin.createApi(baseUrl = server.address)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		loadBackgrounds(setOf(splashscreenUrl))
	}

	/**
	 * Use a direct image URL as background (e.g., TMDB images).
	 * @param blurContext The context to determine which blur amount preference to use
	 */
	fun setBackgroundUrl(imageUrl: String, blurContext: BlurContext = BlurContext.BROWSING) {
		// Check if backgrounds are enabled
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Set blur context
		_blurContext.value = blurContext

		loadBackgrounds(setOf(imageUrl))
	}

	fun setBackgroundFromLibrary(libraryId: UUID, blurContext: BlurContext = BlurContext.BROWSING) {
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		_blurContext.value = blurContext

		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			try {
				// Try to find an item with a backdrop image
				val response by api.itemsApi.getItems(
					parentId = libraryId,
					recursive = true,
					sortBy = listOf(ItemSortBy.RANDOM),
					limit = 1,
					imageTypes = listOf(ImageType.BACKDROP),
				)
				val item = response.items.firstOrNull()
				if (item != null) {
					val backdropUrls = (item.itemBackdropImages + item.parentBackdropImages)
						.take(1)
						.map { it.getUrl(api, maxWidth = backdropMaxWidth) }
						.toSet()
					if (backdropUrls.isNotEmpty()) {
						loadBackgrounds(backdropUrls)
						return@launch
					}
				}

				// Fallback: fetch any item with a primary image
				val fallback by api.itemsApi.getItems(
					parentId = libraryId,
					recursive = true,
					sortBy = listOf(ItemSortBy.RANDOM),
					limit = 1,
					imageTypes = listOf(ImageType.PRIMARY),
				)
				val fallbackItem = fallback.items.firstOrNull()
				if (fallbackItem != null) {
					val primaryUrl = api.imageApi.getItemImageUrl(
						itemId = fallbackItem.id,
						imageType = ImageType.PRIMARY,
						maxWidth = backdropMaxWidth,
					)
					loadBackgrounds(setOf(primaryUrl))
					return@launch
				}
			} catch (e: Exception) {
				Timber.w(e, "Failed to fetch random backdrop from library $libraryId")
			}
			clearBackgrounds()
		}
	}

	private fun loadBackgrounds(backdropUrls: Set<String>) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		_enabled.value = true
		
		val blurAmount = when (_blurContext.value) {
			BlurContext.DETAILS -> userSettingPreferences[UserSettingPreferences.detailsBackgroundBlurAmount]
			BlurContext.BROWSING -> userSettingPreferences[UserSettingPreferences.browsingBackgroundBlurAmount]
			BlurContext.NONE -> 0
		}

		// Blurring on the CPU (legacy devices, and every device while browsing): decode at
		// quarter size — the heavy blur hides the resolution loss and it cuts decode + blur +
		// GPU upload cost ~4x, which matters while these loads race D-pad scrolling.
		val cpuBlur = !usesComposeBlur(_blurContext.value) && blurAmount > 0
		val decodeSize = if (cpuBlur) Size(480, 270) else Size(960, 540)

		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			// The first picture shows as soon as it is ready; the others (a details screen's
			// slideshow) follow without holding it back.
			val loaded = mutableListOf<ImageBitmap>()
			for (url in backdropUrls) {
				val bitmap = imageLoader.execute(
					request = ImageRequest.Builder(context).data(url)
						// Downscale for background use — full resolution is unnecessary
						// and significantly slows blur processing on TV hardware
						.size(decodeSize)
						.build()
				).image?.toBitmap() ?: continue

				loaded += if (cpuBlur) BitmapBlur.blur(bitmap, blurAmount).asImageBitmap() else bitmap.asImageBitmap()
				// Published on the main thread, and never by a load a newer one replaced (the blur
				// can't be cancelled midway).
				ensureActive()
				val published = loaded.toList()
				withContext(Dispatchers.Main) {
					_backgrounds = published
					if (published.size == 1) {
						_currentIndex = 0
						update()
					} else if (published.size == 2 && _currentBackground.value === published[0]) {
						// A second backdrop arrived: start the slideshow. (While the first still
						// waits for the transition guard, that update starts it.)
						setTimer()
					}
				}
			}
			if (loaded.isEmpty()) {
				ensureActive()
				withContext(Dispatchers.Main) {
					_backgrounds = emptyList()
					_currentIndex = 0
					update()
				}
			}
		}
	}

	fun clearBackgrounds() {
		loadBackgroundsJob?.cancel()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		if (_backgrounds.isEmpty()) return

		_backgrounds = emptyList()
		update()
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}
