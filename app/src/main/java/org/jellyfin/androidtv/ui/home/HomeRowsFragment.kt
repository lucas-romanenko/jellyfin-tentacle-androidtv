package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.view.isVisible
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ClassPresenterSelector
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.data.service.BlurContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.AggregatedItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.home.mediabar.MediaBarSlideshowViewModel
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.ThemeMusicPlayer
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.Debouncer
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val sessionRepository by inject<SessionRepository>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val mediaBarViewModel by inject<MediaBarSlideshowViewModel>()
	private val themeMusicPlayer by inject<ThemeMusicPlayer>()
	private val tentacleRepository by inject<TentacleRepository>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Flow to track selected row position
	private val _selectedPositionFlow = MutableStateFlow(0)
	val selectedPositionFlow: StateFlow<Int> = _selectedPositionFlow.asStateFlow()

	// Flow to track selected item for split view display
	private val _selectedItemStateFlow = MutableStateFlow(SelectedItemState.EMPTY)
	val selectedItemStateFlow: StateFlow<SelectedItemState> = _selectedItemStateFlow.asStateFlow()

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository, navigationRepository) }
	private val mediaBarRow by lazy { HomeFragmentMediaBarRow(lifecycleScope, mediaBarViewModel) }

	// Store rows for refreshing
	private var currentRows = mutableListOf<HomeFragmentRow>()
	private var playlistsRow: HomeFragmentPlaylistsRow? = null

	// Debouncer for selection updates - only update UI after user stops navigating
	private val selectionDebouncer by lazy { Debouncer(150.milliseconds, lifecycleScope) }
	private val backgroundDebouncer by lazy { Debouncer(200.milliseconds, lifecycleScope) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a custom row presenter that keeps headers always visible
		val zoomFactor = if (userPreferences[UserPreferences.cardFocusExpansion])
			androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_MEDIUM
		else
			androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_NONE
		val rowPresenter = PositionableListRowPresenter(requireContext(), focusZoomFactor = zoomFactor).apply {
			// Enable select effect for rows
			setSelectEffectEnabled(true)
		}

		// Create presenter selector to handle different row types
		val presenterSelector = ClassPresenterSelector().apply {
			addClassPresenter(ListRow::class.java, rowPresenter)
			addClassPresenter(MediaBarRow::class.java, MediaBarPresenter(mediaBarViewModel, navigationRepository))
		}

		adapter = MutableObjectAdapter<Row>(presenterSelector)

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Start out with default sections
			val homesections = userSettingPreferences.activeHomesections
			var includeLiveTvRows = false

			// Pre-fetch views and check live TV support in parallel
			val viewsDeferred = if (homesections.contains(HomeSectionType.LATEST_MEDIA)) {
				async { userViewsRepository.views.first() }
			} else null

			val liveTvDeferred = if (homesections.contains(HomeSectionType.LIVE_TV) && currentUser.policy?.enableLiveTvAccess == true) {
				async {
					val recommendedPrograms by api.liveTvApi.getRecommendedPrograms(
						enableTotalRecordCount = false,
						imageTypeLimit = 1,
						isAiring = true,
						limit = 1,
					)
					recommendedPrograms.items.isNotEmpty()
				}
			} else null

			includeLiveTvRows = liveTvDeferred?.await() ?: false
			val cachedViews = viewsDeferred?.await()

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Try to load Tentacle dashboard sections (playlists + built-in Jellyfin sections).
			// The Tentacle dashboard controls the full row order including built-in sections.
			val tentacleAvailable = tentacleRepository.checkAvailable()

			// Only add media bar row if Tentacle hero is actually enabled and configured
			if (userSettingPreferences[UserSettingPreferences.mediaBarEnabled] && tentacleAvailable) {
				val heroConfig = tentacleRepository.getHeroConfig()
				Timber.d("Hero config check: config=$heroConfig, enabled=${heroConfig?.enabled}, playlistId=${heroConfig?.playlistId}")
				if (heroConfig != null && heroConfig.enabled && heroConfig.playlistId.isNotEmpty()) {
					rows.add(mediaBarRow)
					// Apply trailer audio setting from dashboard config
					userSettingPreferences[UserSettingPreferences.previewAudioEnabled] = heroConfig.trailerAudio
					Timber.d("MediaBar row added (hero enabled, trailerAudio=${heroConfig.trailerAudio})")
				} else {
					Timber.d("MediaBar row skipped (hero disabled or no config)")
				}
			} else {
				Timber.d("MediaBar row skipped (mediaBarEnabled=${userSettingPreferences[UserSettingPreferences.mediaBarEnabled]}, tentacleAvailable=$tentacleAvailable)")
			}
			var tentacleSections: List<org.jellyfin.androidtv.data.repository.TentacleSection> = emptyList()

			if (tentacleAvailable) {
				val sectionsResponse = tentacleRepository.getSections()
				if (sectionsResponse != null) {
					tentacleSections = sectionsResponse.sections.filter { it.type == "row" || it.type == "builtin" }

					// Pre-fetch playlist row items in parallel
					val tentacleRowData = tentacleSections
						.filter { it.type == "row" && !it.playlistId.isNullOrEmpty() }
						.map { section ->
							async {
								TentacleRowData(
									title = section.displayText,
									playlistId = section.playlistId!!,
									items = tentacleRepository.getSectionItems(section.playlistId),
								)
							}
						}
						.awaitAll()
						.filter { it.items.isNotEmpty() }

					val tentacleMap = tentacleRowData.associateBy { it.playlistId }

					// Render sections in dashboard order
					for (section in tentacleSections) {
						if (!isActive) return@launch
						when (section.type) {
							"row" -> {
								val playlistId = section.playlistId ?: continue
								tentacleMap[playlistId]?.let { data ->
									rows.add(HomeFragmentTentacleRow(listOf(data)))
								}
							}
							"builtin" -> {
								val sectionId = section.sectionId ?: continue
								addBuiltInSection(rows, sectionId, includeLiveTvRows, cachedViews)
							}
						}
					}
				}
			}

			// If no Tentacle dashboard, fall back to standard Moonfin home sections
			if (tentacleSections.isEmpty()) {
				val mergeContinueWatching = userPreferences[UserPreferences.mergeContinueWatchingNextUp]
				var mergedRowAdded = false

				for (section in homesections) when (section) {
					HomeSectionType.MEDIA_BAR -> { /* Now handled by separate toggle above */ }
					HomeSectionType.LATEST_MEDIA -> rows.add(helper.loadRecentlyAdded(cachedViews ?: userViewsRepository.views.first()))
					HomeSectionType.RECENTLY_RELEASED -> rows.add(helper.loadRecentlyReleased())
					HomeSectionType.LIBRARY_TILES_SMALL -> rows.add(HomeFragmentViewsRow(small = false))
					HomeSectionType.LIBRARY_BUTTONS -> rows.add(HomeFragmentViewsRow(small = true))
					HomeSectionType.RESUME -> {
						if (mergeContinueWatching && !mergedRowAdded) {
							rows.add(helper.loadMergedContinueWatching())
							mergedRowAdded = true
						} else if (!mergeContinueWatching) {
							rows.add(helper.loadResumeVideo())
						}
					}
					HomeSectionType.RESUME_AUDIO -> rows.add(helper.loadResumeAudio())
					HomeSectionType.RESUME_BOOK -> Unit // Books are not (yet) supported
					HomeSectionType.ACTIVE_RECORDINGS -> rows.add(helper.loadLatestLiveTvRecordings())
					HomeSectionType.NEXT_UP -> {
						// Skip Next Up if already merged with Continue Watching
						if (!mergeContinueWatching) {
							rows.add(helper.loadNextUp())
						} else if (!mergedRowAdded) {
							// If user has Next Up but not Resume in their section list, add merged row here
							rows.add(helper.loadMergedContinueWatching())
							mergedRowAdded = true
						}
					}
					HomeSectionType.PLAYLISTS -> {
						val row = helper.loadPlaylists()
						if (row is HomeFragmentPlaylistsRow) {
							this@HomeRowsFragment.playlistsRow = row
							rows.add(row)
						}
					}
					HomeSectionType.LIVE_TV -> if (includeLiveTvRows) {
						rows.add(liveTVRow)
						rows.add(helper.loadOnNow())
					}

					HomeSectionType.NONE -> Unit
				}
			}

			// Store rows for refreshing
			currentRows = rows

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter()

				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Populate info area for the initial selected item — the Leanback
				// selection callback fires before HomeFragment's observers are ready,
				// so the first item's title/summary doesn't show without this.
				view?.post {
					val firstRow = (0 until adapter.size())
						.map { adapter[it] }
						.filterIsInstance<ListRow>()
						.firstOrNull() ?: return@post
					val firstItem = (firstRow.adapter as? MutableObjectAdapter<*>)
						?.let { ra -> if (ra.size() > 0) ra[0] else null } as? BaseRowItem
						?: return@post
					_selectedItemStateFlow.value = SelectedItemState(
						title = firstItem.getName(requireContext()) ?: "",
						summary = firstItem.getSummary(requireContext()) ?: "",
						baseItem = firstItem.baseItem
					)
				}
			}
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(liveTVRow::onItemClicked)
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		var lastMergeState = userPreferences[UserPreferences.mergeContinueWatchingNextUp]
		var lastFocusExpansion = userPreferences[UserPreferences.cardFocusExpansion]
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				while (true) {
					delay(500.milliseconds)
					val currentMergeState = userPreferences[UserPreferences.mergeContinueWatchingNextUp]
					val currentFocusExpansion = userPreferences[UserPreferences.cardFocusExpansion]
					if (currentMergeState != lastMergeState || currentFocusExpansion != lastFocusExpansion) {
						lastMergeState = currentMergeState
						lastFocusExpansion = currentFocusExpansion
						// Recreate the fragment to rebuild rows with new structure
						parentFragmentManager.beginTransaction()
							.detach(this@HomeRowsFragment)
							.commitNow()
						parentFragmentManager.beginTransaction()
							.attach(this@HomeRowsFragment)
							.commitNow()
					}
				}
			}
		}

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)
			}
		}
		
		// Listen for session/user changes and recreate the fragment with fresh data
		lifecycleScope.launch {
			sessionRepository.currentSession
				.onEach { session ->
					// When session changes (user switch), recreate fragment to load new user's data
					if (session != null && adapter.size() > 0) {
						Timber.i("Session changed to user ${session.userId}, recreating home fragment")
						parentFragmentManager.beginTransaction()
							.detach(this@HomeRowsFragment)
							.commitNow()
						parentFragmentManager.beginTransaction()
							.attach(this@HomeRowsFragment)
							.commitNow()
					}
				}
				.launchIn(this)
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		
		verticalGridView?.apply {
			// Reduce item prefetch distance for faster initial load
			setItemViewCacheSize(20)
			
			// Intercept DPAD_LEFT before HorizontalGridView consumes it.
			// HorizontalGridView eats DPAD_LEFT even at position 0, so the only
			// way to transfer focus to the sidebar is to intercept first.
			setOnKeyInterceptListener { event ->
				if (event.action == android.view.KeyEvent.ACTION_DOWN &&
					event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
					val focusedView = findFocus()
					val horizontalGrid = findParentHorizontalGridView(focusedView)
					if (horizontalGrid != null && horizontalGrid.selectedPosition == 0) {
						try {
							val sidebar = requireActivity().findViewById<View?>(org.jellyfin.androidtv.R.id.sidebar)
							if (sidebar != null && sidebar.isVisible) {
								sidebar.requestFocus()
								return@setOnKeyInterceptListener true
							}
						} catch (_: Throwable) { }
					}
				}
				false
			}
			
			// Handle upward navigation from first row to toolbar
			setOnKeyListener { _, keyCode, event ->
				if (event.action == android.view.KeyEvent.ACTION_DOWN &&
					keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
					selectedPosition == 0) {
					try {
						val decor = requireActivity().window.decorView
						val toolbarActions = decor.findViewById<View?>(org.jellyfin.androidtv.R.id.toolbar_actions)
						if (toolbarActions != null && toolbarActions.isFocusable) {
							toolbarActions.requestFocus()
							return@setOnKeyListener true
						}
					} catch (_: Throwable) { }
				}
				false
			}
		}
	}

	/**
	 * Walk up the view hierarchy from the focused view to find the containing HorizontalGridView.
	 */
	private fun findParentHorizontalGridView(view: View?): androidx.leanback.widget.HorizontalGridView? {
		var current = view?.parent
		while (current != null) {
			if (current is androidx.leanback.widget.HorizontalGridView) return current
			current = current.parent
		}
		return null
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		// React to deletion — remove from ALL rows, not just the current one
		val deletedId = dataRefreshService.lastDeletedItemId
		if (deletedId != null) {
			dataRefreshService.lastDeletedItemId = null
			val rowsAdapter = adapter as? MutableObjectAdapter<Row>
			if (rowsAdapter != null) {
				for (i in 0 until rowsAdapter.size()) {
					val row = rowsAdapter.get(i) as? ListRow ?: continue
					val itemAdapter = row.adapter as? ItemRowAdapter ?: continue
					for (j in 0 until itemAdapter.size()) {
						val wrapper = itemAdapter.get(j) as? BaseRowItem ?: continue
						if (wrapper.baseItem?.id == deletedId) {
							itemAdapter.remove(wrapper)
							if (currentItem == wrapper) currentItem = null
							break
						}
					}
				}
			}
		}

		if (justLoaded) {
			justLoaded = false
			// Load initial content on first load
			mediaBarViewModel.loadInitialContent()
		}

		// Update audio queue — deferred to avoid calling commitNow() during an active fragment transaction
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		view?.post { nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>) }

		// Ensure focus is restored to the grid when returning from other screens (like search)
		// This prevents the issue where users can't control the media bar after backing out
		view?.postDelayed({
			if (isResumed && verticalGridView != null && !verticalGridView!!.hasFocus()) {
				verticalGridView?.requestFocus()
			}
		}, 100) // Small delay to let the fragment fully resume
	}

	override fun onPause() {
		super.onPause()
		
		// Stop theme music immediately when fragment is paused
		themeMusicPlayer.stop()
	}

	override fun onPlaybackStateChange(newState: PlaybackController.PlaybackState, currentItem: BaseItemDto?) = Unit

	override fun onProgress(pos: Long, duration: Long) = Unit

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onQueueReplaced() = Unit

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch {
			if (delayed) delay(1.5.seconds)

			// Must run on Main thread: Retrieve()/ReRetrieveIfNeeded() may call loadStaticItems()
			// which adds items directly to the Leanback adapter (UI operation)
			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}

			// Refresh playlists row
			playlistsRow?.refresh()
		}
	}

	private suspend fun addBuiltInSection(
		rows: MutableList<HomeFragmentRow>,
		sectionId: String,
		includeLiveTvRows: Boolean,
		cachedViews: Collection<org.jellyfin.sdk.model.api.BaseItemDto>?,
	) {
		val mergeContinueWatching = userPreferences[UserPreferences.mergeContinueWatchingNextUp]
		when (sectionId) {
			"latestmedia" -> rows.add(helper.loadRecentlyAdded(cachedViews ?: userViewsRepository.views.first()))
			"recentlyreleased" -> rows.add(helper.loadRecentlyReleased())
			"smalllibrarytiles" -> rows.add(HomeFragmentViewsRow(small = false))
			"smalllibrarytiles_small", "librarybuttons" -> rows.add(HomeFragmentViewsRow(small = true))
			"resume", "resumevideo" -> {
				if (mergeContinueWatching) rows.add(helper.loadMergedContinueWatching())
				else rows.add(helper.loadResumeVideo())
			}
			"resumeaudio" -> rows.add(helper.loadResumeAudio())
			"activerecordings" -> rows.add(helper.loadLatestLiveTvRecordings())
			"nextup" -> {
				if (!mergeContinueWatching) rows.add(helper.loadNextUp())
			}
			"playlists" -> {
				val row = helper.loadPlaylists()
				if (row is HomeFragmentPlaylistsRow) {
					this@HomeRowsFragment.playlistsRow = row
					rows.add(row)
				}
			}
			"livetv" -> if (includeLiveTvRows) {
				rows.add(liveTVRow)
				rows.add(helper.loadOnNow())
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
		
		// Stop any playing theme music
		themeMusicPlayer.stop()
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			// Update selected position flow immediately (for focus tracking)
			_selectedPositionFlow.value = selectedPosition
			
			if (item !is BaseRowItem) {
				currentItem = null
				// Clear selected item state immediately
				selectionDebouncer.cancel()
				_selectedItemStateFlow.value = SelectedItemState.EMPTY
				
				// Cancel any pending theme music playback
				themeMusicPlayer.cancelDelayedPlay()
				
				// Don't clear background if we're on the media bar row - it has its own backdrop
				if (row !is MediaBarRow) {
					backgroundService.clearBackgrounds()
				}
			} else {
				currentItem = item
				currentRow = row as ListRow

				// Handle pagination for both ItemRowAdapter and AggregatedItemRowAdapter
				when (val adapter = row.adapter) {
					is ItemRowAdapter -> adapter.loadMoreItemsIfNeeded(adapter.indexOf(item))
					is AggregatedItemRowAdapter -> {
						val pos = adapter.indexOf(item)
						Timber.d("HomeRowsFragment: AggregatedItemRowAdapter selected item at pos=$pos, adapter.size=${adapter.size()}")
						adapter.loadMoreItemsIfNeeded(pos)
					}
				}

				// Debounce UI updates - only update after user stops navigating for 150ms
				selectionDebouncer.debounce {
					_selectedItemStateFlow.value = SelectedItemState(
						title = item.getName(requireContext()) ?: "",
						summary = item.getSummary(requireContext()) ?: "",
						baseItem = item.baseItem
					)
				}

				backgroundDebouncer.debounce {
					val baseItem = item.baseItem
					val adapter = (row as? ListRow)?.adapter as? ItemRowAdapter
					if (adapter?.queryType == QueryType.Views && baseItem != null) {
						backgroundService.setBackgroundFromLibrary(baseItem.id, BlurContext.BROWSING)
					} else {
						backgroundService.setBackground(baseItem, BlurContext.BROWSING)
					}
				}
				
				// Play theme music on focus if enabled (with delay)
				item.baseItem?.let { baseItem ->
					themeMusicPlayer.playThemeMusicOnFocusDelayed(baseItem)
				}
			}
		}
	}
}
