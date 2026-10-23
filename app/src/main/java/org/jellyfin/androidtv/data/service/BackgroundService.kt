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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
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

		// How long another title's picture may stay while the latest one loads (#14)
		val STALE_GRACE = 1.seconds
	}

	// What a request shows: its image urls with their blur, or a library's random backdrop
	private data class UrlsKey(val urls: Set<String>, val blurContext: BlurContext)
	private data class LibraryKey(val libraryId: UUID, val blurContext: BlurContext)

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var staleFadeJob: Job? = null
	// Only the latest request's picture goes on screen (#14)
	private val requests = BackdropRequests()
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

		// The same library tile again keeps the random backdrop it got
		val ticket = startRequest(LibraryKey(libraryId, blurContext)) ?: return
		val decode = decodeOptions(blurContext)
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			val urls = try {
				randomLibraryBackdrop(libraryId)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Timber.w(e, "Failed to fetch random backdrop from library $libraryId")
				emptySet()
			}
			decodeAndPublish(urls, ticket, decode)
		}
	}

	// A random item of the library with a backdrop, else one with a primary image (IO)
	private suspend fun randomLibraryBackdrop(libraryId: UUID): Set<String> {
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
			if (backdropUrls.isNotEmpty()) return backdropUrls
		}

		val fallback by api.itemsApi.getItems(
			parentId = libraryId,
			recursive = true,
			sortBy = listOf(ItemSortBy.RANDOM),
			limit = 1,
			imageTypes = listOf(ImageType.PRIMARY),
		)
		val fallbackItem = fallback.items.firstOrNull() ?: return emptySet()
		return setOf(
			api.imageApi.getItemImageUrl(
				itemId = fallbackItem.id,
				imageType = ImageType.PRIMARY,
				maxWidth = backdropMaxWidth,
			)
		)
	}

	private fun loadBackgrounds(backdropUrls: Set<String>) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		val blurContext = _blurContext.value
		val ticket = startRequest(UrlsKey(backdropUrls, blurContext)) ?: return
		val decode = decodeOptions(blurContext)
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			decodeAndPublish(backdropUrls, ticket, decode)
		}
	}

	/**
	 * Makes [key] the latest request (#14): older loads are cancelled and can no longer publish.
	 * Returns its ticket, or null when there is nothing to load: [key] is on screen already, or
	 * its load is running. While it loads, a picture of an earlier request fades out after
	 * [STALE_GRACE] rather than staying behind another title. Main thread.
	 */
	private fun startRequest(key: Any): Long? {
		_enabled.value = true
		if (requests.isLatestKey(key) && (requests.alreadyShown(key) || loadBackgroundsJob?.isActive == true)) return null

		cancelLoads()
		val ticket = requests.start(key)
		if (requests.alreadyShown(key)) {
			// Back to what is still on screen (focus left a card and came back before the
			// other one loaded): keep it
			requests.publish(ticket)
			return null
		}
		if (requests.shownKey != null) {
			staleFadeJob = scope.launch {
				delay(STALE_GRACE)
				if (requests.fadesOut(ticket)) {
					_backgrounds = emptyList()
					_currentIndex = 0
					update()
				}
			}
		}
		return ticket
	}

	private fun cancelLoads() {
		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = null
		staleFadeJob?.cancel()
		staleFadeJob = null
	}

	private class DecodeOptions(val cpuBlur: Boolean, val blurAmount: Int, val size: Size)

	private fun decodeOptions(blurContext: BlurContext): DecodeOptions {
		val blurAmount = when (blurContext) {
			BlurContext.DETAILS -> userSettingPreferences[UserSettingPreferences.detailsBackgroundBlurAmount]
			BlurContext.BROWSING -> userSettingPreferences[UserSettingPreferences.browsingBackgroundBlurAmount]
			BlurContext.NONE -> 0
		}

		// Blurring on the CPU (legacy devices, and every device while browsing): decode at
		// quarter size — the heavy blur hides the resolution loss and it cuts decode + blur +
		// GPU upload cost ~4x, which matters while these loads race D-pad scrolling.
		val cpuBlur = !usesComposeBlur(blurContext) && blurAmount > 0
		return DecodeOptions(cpuBlur, blurAmount, if (cpuBlur) Size(480, 270) else Size(960, 540))
	}

	// Loads [backdropUrls] for request [ticket] and shows them, as long as it is the latest one.
	private suspend fun decodeAndPublish(backdropUrls: Set<String>, ticket: Long, decode: DecodeOptions) {
		// The first picture shows as soon as it is ready; the others (a details screen's
		// slideshow) follow without holding it back.
		val loaded = mutableListOf<ImageBitmap>()
		for (url in backdropUrls) {
			val bitmap = imageLoader.execute(
				request = ImageRequest.Builder(context).data(url)
					// Downscale for background use — full resolution is unnecessary
					// and significantly slows blur processing on TV hardware
					.size(decode.size)
					.build()
			).image?.toBitmap() ?: continue

			currentCoroutineContext().ensureActive()
			loaded += if (decode.cpuBlur) BitmapBlur.blur(bitmap, decode.blurAmount).asImageBitmap() else bitmap.asImageBitmap()
			// Published on the main thread, and only while this is the latest request: a newer
			// one may have started while this decoded or blurred (the blur can't be cancelled
			// midway).
			val published = loaded.toList()
			val shown = withContext(Dispatchers.Main) {
				if (!requests.publish(ticket)) return@withContext false
				staleFadeJob?.cancel()
				_backgrounds = published
				if (published.size == 1) {
					_currentIndex = 0
					update()
				} else if (published.size == 2 && _currentBackground.value === published[0]) {
					// A second backdrop arrived: start the slideshow. (While the first still
					// waits for the transition guard, that update starts it.)
					setTimer()
				}
				true
			}
			if (!shown) return
		}
		if (loaded.isEmpty()) {
			// No picture (none, or none loaded): neutral, if this is still the latest request
			withContext(Dispatchers.Main) {
				if (!requests.publishNothing(ticket)) return@withContext
				staleFadeJob?.cancel()
				_backgrounds = emptyList()
				_currentIndex = 0
				update()
			}
		}
	}

	fun clearBackgrounds() {
		cancelLoads()
		// A clear is a request too: it wins over every older load still running
		requests.publishNothing(requests.start(null))

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
