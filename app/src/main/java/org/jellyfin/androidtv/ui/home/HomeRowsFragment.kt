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
import org.jellyfin.androidtv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.HomeVersionTracker
import org.jellyfin.androidtv.data.repository.SectionsAnswer
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.SectionSource
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.TentacleSectionsResponse
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.data.service.BlurContext
import org.jellyfin.androidtv.preference.SystemPreferences
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
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The Live TV rows check, run as an `async` child of the home build: a failed request answers
 * null ("unknown") instead of failing the child, which would fail the home build's coroutine
 * and, with no handler on lifecycleScope, crash the app. Cancellation still propagates.
 */
internal suspend fun liveTvProbe(check: suspend () -> Boolean): Boolean? = try {
	check()
} catch (e: CancellationException) {
	throw e
} catch (e: Exception) {
	Timber.w("Live TV check failed: ${e.javaClass.simpleName} (${e.message})")
	null
}

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
	private val systemPreferences by inject<SystemPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()
	private val mediaBarViewModel by inject<MediaBarSlideshowViewModel>()
	private val themeMusicPlayer by inject<ThemeMusicPlayer>()
	private val tentacleRepository by inject<TentacleRepository>()
	private val homeVersionTracker by inject<HomeVersionTracker>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Flow to track selected row position
	private val _selectedPositionFlow = MutableStateFlow(0)
	val selectedPositionFlow: StateFlow<Int> = _selectedPositionFlow.asStateFlow()

	// Flow to track selected item for split view display
	private val _selectedItemStateFlow = MutableStateFlow(SelectedItemState.EMPTY)
	val selectedItemStateFlow: StateFlow<SelectedItemState> = _selectedItemStateFlow.asStateFlow()

	// Signal that rows have been rendered and are ready to display
	private val _contentReady = MutableStateFlow(false)
	val contentReady: StateFlow<Boolean> = _contentReady.asStateFlow()

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var suppressSelectionClearing = false
	var hasMediaBarAtPosition0 = false
		private set
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository, navigationRepository) }
	private val mediaBarRow by lazy { HomeFragmentMediaBarRow(lifecycleScope, mediaBarViewModel) }

	// Store rows for refreshing
	private var currentRows = mutableListOf<HomeFragmentRow>()
	private var playlistsRow: HomeFragmentPlaylistsRow? = null
	// Home's rows in order and its built-in rows, as built: a built-in row that left empty is
	// asked again and comes back in its place (#140).
	private var homeRowOrder: List<Row> = emptyList()
	private var stockRows: List<ListRow> = emptyList()

	// Track Tentacle playlist rows for in-place refresh (playlistId → ItemRowAdapter)
	private val tentacleRowAdapters = mutableMapOf<String, ItemRowAdapter>()
	// The On Now row's adapter (ON_NOW_ROW_KEY), refilled on every home refresh
	private val onNowAdapters = mutableMapOf<String, ItemRowAdapter>()
	// This home has an On Now row (shown or, with nothing on, left out)
	@Volatile private var onNowInHome = false
	// The last build left On Now out because nothing was on (not just "not added yet")
	@Volatile private var onNowLeftOut = false
	// The last in-place rebuild kept the On Now row on screen
	private var onNowReused = false
	// Empty answers in a row per Tentacle row (see TentacleRowRefresh).
	private val emptyRowAnswers = mutableMapOf<String, Int>()
	// The last answer kept as "shrank too far to believe", per playlist: the same answer
	// again means the playlist really is that small (#54).
	private val shrunkRowAnswers = mutableMapOf<String, List<java.util.UUID>>()

	// Track current Tentacle section structure for detecting structural changes
	private var currentTentacleSectionKeys = listOf<String>()

	// Dirty flag: set true when a WebSocket event fires while the fragment is paused.
	// On resume, only refresh if something actually changed — avoids full re-fetch
	// every time the user navigates back from Search/Discover/etc.
	// @Volatile: written from WebSocket coroutines, read/written from onResume (main).
	@Volatile
	private var tentacleDirty = false

	// The home version on screen (0: unknown, an older plugin), and whether the rows on screen
	// are Tentacle's home (false: the stock rows, without the plugin or with its home off).
	@Volatile
	private var shownHomeVersion = 0L
	@Volatile
	private var showingTentacleHome = false

	// Whether the user's layout may show Live TV rows (set by the first load): a rebuild
	// keeps them instead of dropping them (M3).
	@Volatile
	private var liveTvRowsWanted = false

	// Completed when the first load is done: no refresh runs before it (a push during the
	// first load duplicated rows, M3).
	private val firstLoadDone = CompletableDeferred<Unit>()

	// Every Tentacle home refresh goes through here: one at a time, newest wins (M3).
	private lateinit var homeRefresher: SerialRefresher<HomeRefreshRequest>

	// The first load's Tentacle row requests, by playlist id (filled in as they arrive).
	private var tentacleRowFetches: Map<String, kotlinx.coroutines.Deferred<List<BaseItemDto>>> = emptyMap()

	// Index in the adapter where content rows start (after notifications + nowPlaying)
	private var contentRowStartIndex = 0

	// Shared card presenter used by all rows — enables RecycledViewPool sharing
	// across rows, avoiding expensive ComposeView inflation on vertical scroll.
	private lateinit var sharedCardPresenter: CardPresenter

	// Debouncer for selection updates - only update UI after user stops navigating
	private val selectionDebouncer by lazy { Debouncer(150.milliseconds, lifecycleScope) }
	private val backgroundDebouncer by lazy { Debouncer(500.milliseconds, lifecycleScope) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		sharedCardPresenter = CardPresenter(true, org.jellyfin.androidtv.constant.ImageType.POSTER, 150, true)

		// Create a custom row presenter that keeps headers always visible
		val zoomFactor = if (userPreferences[UserPreferences.cardFocusExpansion])
			androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_MEDIUM
		else
			androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_NONE
		val rowPresenter = PositionableListRowPresenter(requireContext(), focusZoomFactor = zoomFactor).apply {
			// Enable select effect for rows
			setSelectEffectEnabled(true)
			// Share card views across rows for faster vertical scrolling.
			// Larger pool avoids expensive ComposeView re-inflation on low-end
			// devices (Chromecast, budget TVs) during vertical scroll.
			setRecycledPoolSize(sharedCardPresenter, 48)
		}

		// Create presenter selector to handle different row types
		val presenterSelector = ClassPresenterSelector().apply {
			addClassPresenter(ListRow::class.java, rowPresenter)
			addClassPresenter(MediaBarRow::class.java, MediaBarPresenter(mediaBarViewModel, navigationRepository))
		}

		adapter = MutableObjectAdapter<Row>(presenterSelector)

		homeRefresher = SerialRefresher<HomeRefreshRequest>(lifecycleScope, firstLoadDone, HomeRefreshRequest::merge, { it.settle }) { request ->
			runHomeRefresh(request)
		}

		lifecycleScope.launch(Dispatchers.IO) {
			// The body's end, not its children's: the Live TV check below may run on.
			try {
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
						liveTvProbe {
							val recommendedPrograms by api.liveTvApi.getRecommendedPrograms(
								enableTotalRecordCount = false,
								imageTypeLimit = 1,
								isAiring = true,
								limit = 1,
							)
							recommendedPrograms.items.isNotEmpty()
						}
					}
				} else null

				// Tentacle's sections, asked at once alongside the views: this request also tells
				// whether the plugin is there, so there is no separate probe of the same endpoint
				// first (H4). An unreachable server falls back to the on-device cache below.
				val sectionsDeferred = async { withTimeoutOrNull(8_000) { tentacleRepository.fetchSections() } }

				// Await views (fast /UserViews call) but NOT the Live TV recommended-programs
				// check — that query scans the whole EPG and can take 10+ seconds on large
				// setups, and awaiting it here used to block the instant cache render below,
				// leaving the home screen blank. Use the persisted result from the previous
				// run for the cache render; the real check revalidates in the background.
				val cachedViews = viewsDeferred?.await()
				includeLiveTvRows = liveTvDeferred != null && systemPreferences[SystemPreferences.liveTvRowsAvailable]
				liveTvRowsWanted = liveTvDeferred != null

				// Revalidate Live TV availability in the background and persist for the
				// next launch — the check scans the whole EPG and must never block a render.
				liveTvDeferred?.let { deferred ->
					launch {
						// No answer keeps the last known value.
						val available = deferred.await() ?: return@launch
						systemPreferences[SystemPreferences.liveTvRowsAvailable] = available
					}
				}

				// Make sure the rows are empty
				val rows = mutableListOf<HomeFragmentRow>()

				// Check for coroutine cancellation
				if (!isActive) return@launch

				// ── Fresh-first load ────────────────────────────────────────────
				// While the loading overlay holds, fetch the authoritative home config
				// from Tentacle and build everything ONCE — rows, hero, order — so the
				// reveal is final with no post-launch restructure. The on-device cache
				// below is only the fallback for an unreachable/slow server.
				val freshAnswer = sectionsDeferred.await()
				HomeTiming.mark("sections answered")
				val freshSections = (freshAnswer as? SectionsAnswer.On)?.response
				// Home off: the stock rows below, at this version (a switch back on is then seen).
				if (freshAnswer is SectionsAnswer.Off) {
					shownHomeVersion = freshAnswer.homeVersion
					homeVersionTracker.noteVersion(freshAnswer.homeVersion)
				}

				// ── Fallback: render from on-device cache ───────────────────────
				// Only when the fresh fetch failed. Renders the last session's layout,
				// then a background refresh revalidates and updates rows in place.
				val cachedSections = if (freshSections == null) tentacleRepository.getCachedSections() else null
				if (cachedSections != null) {
					val sections = cachedSections.sections.filter { it.type == "row" || it.type == "builtin" }
					if (sections.isNotEmpty()) {
						if (userSettingPreferences[UserSettingPreferences.mediaBarEnabled]) {
							val heroConfig = tentacleRepository.getCachedHeroConfig()
							if (heroConfig != null && heroConfig.enabled && heroConfig.playlistId.isNotEmpty()) {
								rows.add(mediaBarRow)
								mediaBarViewModel.loadInitialContent(allowCache = true)
							}
						}
						val cachedMergeCw = cachedSections.mergeContinueWatching
						val cachedHasResume = sections.any { it.type == "builtin" && (it.sectionId == "resume" || it.sectionId == "resumevideo") }
						resetOnNow()
						for (section in sections) {
							when (section.type) {
								"row" -> {
									val playlistId = section.playlistId ?: continue
									val items = tentacleRepository.getCachedSectionItems(playlistId)
									if (items.isNotEmpty()) {
										rows.add(
											HomeFragmentTentacleRow(
												listOf(
													TentacleRowData(
														title = section.displayText,
														playlistId = playlistId,
														items = items,
														wide = section.shape == "wide",
													)
												),
												tentacleRowAdapters,
											)
										)
									}
								}
								"builtin" -> {
									val sectionId = section.sectionId ?: continue
									// Merge on: Next Up folds into the Continue Watching row
									if (cachedMergeCw && sectionId == "nextup" && cachedHasResume) continue
									addBuiltInSection(rows, sectionId, includeLiveTvRows, cachedViews, cachedMergeCw)
								}
							}
						}
						if (rows.any { it !is HomeFragmentMediaBarRow }) {
							// merge flag is part of the structure key so toggling it in the
							// dashboard triggers a structural rebuild on the next refresh
							currentTentacleSectionKeys = sections.map(::sectionKey) + "merge:$cachedMergeCw"
							currentRows = rows
							showingTentacleHome = true
							renderInitialRows(rows)
							Timber.i("Home rendered from cache fallback (${rows.size} rows), revalidating in background")

							// Background revalidation — fetches fresh sections + items and diffs
							// against what's on screen (or shows the stock rows if the home is off).
							runHomeRefresh(HomeRefreshRequest(force = true))
							return@launch
						}
						// Cache produced no content rows — reset and fall through to the network path
						rows.clear()
						tentacleRowAdapters.clear()
					}
				}

				// ── Fresh render path ───────────────────────────────────────────
				// freshSections was fetched above while the loading overlay holds. Build
				// the final layout in one pass; no post-render revalidation needed.
				val tentacleFresh = freshSections != null
				var heroAdded = false

				// Only add media bar row if Tentacle hero is actually enabled and configured. The
				// sections answer says so (its "hero" section): no separate hero-settings request
				// before the rows (H4). The hero loads its settings and items itself, in parallel
				// with the rows, and applies the dashboard's trailer sound (HomeFragment).
				if (userSettingPreferences[UserSettingPreferences.mediaBarEnabled] && tentacleFresh) {
					val heroOn = freshSections?.sections?.any { it.type == "hero" && !it.playlistId.isNullOrEmpty() } == true
					if (heroOn) {
						rows.add(mediaBarRow)
						heroAdded = true
						mediaBarViewModel.loadInitialContent()
						Timber.d("MediaBar row added (hero enabled)")
					} else {
						Timber.d("MediaBar row skipped (hero disabled or no config)")
					}
				} else {
					Timber.d("MediaBar row skipped (mediaBarEnabled=${userSettingPreferences[UserSettingPreferences.mediaBarEnabled]}, tentacleFresh=$tentacleFresh)")
				}
				var tentacleSections: List<org.jellyfin.androidtv.data.repository.TentacleSection> = emptyList()

				if (tentacleFresh) {
					val sectionsResponse = freshSections
					if (sectionsResponse != null) {
						tentacleSections = sectionsResponse.sections.filter { it.type == "row" || it.type == "builtin" }

						// Every playlist row's items are asked for at once (the repository runs 4 at
						// a time, in dashboard order) while the rows, the stock rows and the hero go
						// on screen; each row fills in as its answer arrives (H4). Home used to wait
						// for the slowest row before showing any, and only then load the stock rows.
						// The hero goes first: the loading screen waits for it, and on a slow TV twenty
						// row answers decoding at once held its answer back by seconds. The rows on the
						// first screen start with it, the others once it is answered (3 s at most).
						val firstScreen = firstScreenRows(tentacleSections.map { if (it.type == "row") it.playlistId else null })
						val heroFirst = if (heroAdded) async {
							withTimeoutOrNull(3_000) { mediaBarViewModel.awaitHeroRequests() }
						} else null
						tentacleRowFetches = tentacleSections
							.filter { it.type == "row" && !it.playlistId.isNullOrEmpty() }
							.distinctBy { it.playlistId }
							.associate { section ->
								section.playlistId!! to async {
									if (section.playlistId !in firstScreen) heroFirst?.await()
									tentacleRepository.getSectionItems(section.playlistId)
								}
							}

						// Render sections in dashboard order
						val mergeCw = sectionsResponse.mergeContinueWatching
						val hasResumeRow = tentacleSections.any { it.type == "builtin" && (it.sectionId == "resume" || it.sectionId == "resumevideo") }
						resetOnNow()
						for (section in tentacleSections) {
							if (!isActive) return@launch
							when (section.type) {
								"row" -> {
									val playlistId = section.playlistId ?: continue
									if (playlistId in tentacleRowFetches && rows.none { it is HomeFragmentTentacleRow && playlistId in it.playlistIds }) {
										// Its items arrive later (fillTentacleRow)
										rows.add(
											HomeFragmentTentacleRow(
												listOf(TentacleRowData(section.displayText, playlistId, emptyList(), section.shape == "wide")),
												tentacleRowAdapters,
												placeholder = true,
											)
										)
									}
								}
								"builtin" -> {
									val sectionId = section.sectionId ?: continue
									// Merge on: Next Up folds into the Continue Watching row
									if (mergeCw && sectionId == "nextup" && hasResumeRow) continue
									addBuiltInSection(rows, sectionId, includeLiveTvRows, cachedViews, mergeCw)
								}
							}
						}

						// Store section keys for detecting structural changes later (the
						// merge flag is included so toggling it rebuilds rows in place)
						currentTentacleSectionKeys = tentacleSections.map(::sectionKey) + "merge:$mergeCw"
						shownHomeVersion = sectionsResponse.homeVersion
						homeVersionTracker.noteVersion(sectionsResponse.homeVersion)
						showingTentacleHome = tentacleSections.isNotEmpty()
					}
				}

				// If no Tentacle dashboard, fall back to standard home sections
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
				val fetches = tentacleRowFetches
				if (fetches.isEmpty()) {
					renderInitialRows(rows)
				} else {
					renderInitialRows(rows, contentReady = false)
					fillTentacleRows(rows, fetches)
				}
				HomeTiming.mark("all rows filled")
			} finally {
				firstLoadDone.complete(Unit)
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

		// Listen for preference changes that require a full rows rebuild
		val watchedKeys = setOf(
			UserPreferences.mergeContinueWatchingNextUp.key,
			UserPreferences.cardFocusExpansion.key,
		)
		val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			if (key in watchedKeys && isAdded && !isStateSaved) {
				parentFragmentManager.beginTransaction()
					.replace(R.id.rowsFragment, HomeRowsFragment())
					.commitNow()
			}
		}
		userPreferences.registerChangeListener(prefListener)
		lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
			override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
				userPreferences.unregisterChangeListener(prefListener)
			}
		})

		// Live settings (androidtv audit H2, H3, M3). SocketHandler hears every LibraryChanged
		// message while the app is in front, also while Home isn't on screen, and keeps the
		// newest home version (HomeVersionTracker). On screen, Home refreshes on a push; off
		// screen it catches up in onResume, by comparing the version it shows with the
		// server's. Every refresh goes through homeRefresher: one at a time, newest wins.
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch {
					homeVersionTracker.changes.collect { change ->
						val push = change.push
						if (!isResumed) {
							// A versioned push is caught up by version in onResume; one without
							// a version (an older plugin, a library scan) is remembered here.
							if (push == null) tentacleDirty = true
							return@collect
						}
						Timber.i("WS: LibraryChanged on Home (home version ${push?.version ?: "none"})")
						homeRefresher.request(
							when {
								// Coalesce bursts of events into a single refresh. The backend fires
								// one on every playlist mutation, and a playlist refresh clears and
								// re-adds items: waiting until events settle (and Jellyfin indexes)
								// keeps rows from blanking out and reordering.
								push == null -> HomeRefreshRequest(stockRows = true, force = true, settle = 3.seconds)
								push.everyone -> HomeRefreshRequest(stockRows = true, settle = 3.seconds)
								// A save of this user's home: the stock rows didn't change.
								else -> HomeRefreshRequest(settle = 1.seconds)
							}
						)
					}
				}

				launch {
					homeVersionTracker.reconnects.collect {
						// The socket was lost and is back: a save meanwhile wasn't heard (H3).
						if (isResumed) homeRefresher.request(HomeRefreshRequest())
					}
				}
			}
		}

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				while (isActive) {
					try {
						api.webSocket.subscribe<UserDataChangedMessage>()
							.collect {
								Timber.w("WS: UserDataChangedMessage received")
								refreshRows(force = false, delayed = true)
								// A channel starred or unstarred anywhere (Tentacle's My Channels,
								// the guide, another device) reaches On Now at once
								refreshOnNow()
							}
					} catch (e: kotlinx.coroutines.CancellationException) {
						throw e
					} catch (e: Exception) {
						Timber.w(e, "WS: userdata subscription error")
					}
					Timber.w("WS: userdata subscription ended — resubscribing in 5s")
					delay(5.seconds)
				}
			}
		}

		// On Now while Home is showing: programmes end, time left moves on
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				while (isActive) {
					delay(ON_NOW_REFRESH)
					refreshOnNow()
				}
			}
		}

		// Listen for session/user changes and recreate the fragment with fresh data
		lifecycleScope.launch {
			sessionRepository.currentSession
				.onEach { session ->
					// When session changes (user switch), clear caches and recreate fragment.
					// Guard against committing after state save (would throw IllegalStateException).
					if (session != null && adapter.size() > 0 && isAdded && !isStateSaved) {
						tentacleRepository.resetAvailabilityCache()
						Timber.i("Session changed to user ${session.userId}, recreating home fragment")
						parentFragmentManager.beginTransaction()
							.replace(R.id.rowsFragment, HomeRowsFragment())
							.commitNow()
					}
				}
				.launchIn(this)
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	/**
	 * Add built rows to the Leanback adapter, publish the initial selected item,
	 * and flag content ready. Shared by the instant
	 * cache render and the fresh network render.
	 */
	private suspend fun renderInitialRows(rows: List<HomeFragmentRow>, contentReady: Boolean = true) {
		withContext(Dispatchers.Main) {
			// The fragment may have been detached while we were loading on IO
			// (fast navigation, or a user-switch / pref-change recreate). Touching
			// requireContext()/requireActivity() below would throw
			// IllegalStateException and crash the app, so bail out cleanly.
			if (!isActive || !isAdded) return@withContext
			// Add rows in order — all rows use the shared card presenter
			// for RecycledViewPool sharing across rows
			notificationsRow.addToRowsAdapter(requireContext(), sharedCardPresenter, adapter as MutableObjectAdapter<Row>)
			nowPlaying.addToRowsAdapter(requireContext(), sharedCardPresenter, adapter as MutableObjectAdapter<Row>)
			contentRowStartIndex = adapter.size() // Mark where content rows begin
			hasMediaBarAtPosition0 = rows.firstOrNull() is HomeFragmentMediaBarRow
			for (row in rows) row.addToRowsAdapter(requireContext(), sharedCardPresenter, adapter as MutableObjectAdapter<Row>)
			trackStockRows()
			view?.let { v -> androidx.core.view.OneShotPreDrawListener.add(v) { HomeTiming.mark("rows drawn (${rows.size} rows)") } }

			if (contentReady) markContentReady()

			// No poster pre-warm: it asked for a different URL and size than the cards do, so
			// it decoded up to 20 full posters nothing reused, during the first render.
		}
	}

	/**
	 * Note Home's rows as just built, and let each built-in row come back once it has items
	 * after it removed itself empty (#140). Right after the rows are added: they leave only
	 * when their first answer arrives. Main thread.
	 */
	private fun trackStockRows() {
		val rowsAdapter = adapter as? MutableObjectAdapter<Row> ?: return
		val tentacleAdapters = tentacleRowAdapters.values.toSet()
		homeRowOrder = rowsAdapter.toList()
		stockRows = homeRowOrder.filterIsInstance<ListRow>().filter { row ->
			val rowAdapter = row.adapter as? ItemRowAdapter
			rowAdapter != null && rowAdapter !in tentacleAdapters
		}
		for (row in stockRows) {
			(row.adapter as ItemRowAdapter).setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
				override fun onResponse() = putBackIfFilled(row)
			})
		}
	}

	/** A built-in row that left Home empty and has items again goes back in its place (#140). */
	private fun putBackIfFilled(row: ListRow) {
		val rowsAdapter = adapter as? MutableObjectAdapter<Row> ?: return
		if (!isAdded || row !in stockRows || (row.adapter?.size() ?: 0) == 0) return
		val shown = rowsAdapter.toList()
		if (row in shown) return
		Timber.i("Built-in row '${row.headerItem?.name}' has items again, putting it back")
		rowsAdapter.add(HiddenStockRows.returnIndex(homeRowOrder, row, shown), row)
	}

	/**
	 * The rows on screen are ready to show: fill the info area for the first item (the Leanback
	 * selection callback fires before HomeFragment's observers are ready, so the first item's
	 * title/summary doesn't show without this) and let the loading screen go. Main thread.
	 */
	private fun markContentReady() {
		if (_contentReady.value) return
		view?.post {
			if (!isAdded) return@post
			val firstRow = (0 until adapter.size())
				.map { adapter[it] }
				.filterIsInstance<ListRow>()
				.firstOrNull { (it.adapter?.size() ?: 0) > 0 } ?: return@post
			val firstItem = (firstRow.adapter as? MutableObjectAdapter<*>)
				?.let { ra -> if (ra.size() > 0) ra[0] else null } as? BaseRowItem
				?: return@post
			_selectedItemStateFlow.value = SelectedItemState(
				title = firstItem.getName(requireContext()) ?: "",
				summary = firstItem.getSummary(requireContext()) ?: "",
				baseItem = firstItem.baseItem
			)
		}
		_contentReady.value = true
	}

	/**
	 * Fills each Tentacle row of the first load as its items arrive (H4): a row with items
	 * shows them, an empty one leaves. The loading screen waits only for the rows it uncovers
	 * first ([firstScreenRows]); the rest fill in below. Returns when every row is done.
	 */
	private suspend fun fillTentacleRows(rows: List<HomeFragmentRow>, fetches: Map<String, kotlinx.coroutines.Deferred<List<BaseItemDto>>>) {
		val waitFor = firstScreenRows(
			rows.filterNot { it is HomeFragmentMediaBarRow }
				.map { row -> (row as? HomeFragmentTentacleRow)?.playlistIds?.firstOrNull()?.takeIf { it in fetches } }
		).toMutableSet()
		if (waitFor.isEmpty()) withContext(Dispatchers.Main) { markContentReady() }
		kotlinx.coroutines.coroutineScope {
			for ((playlistId, fetch) in fetches) launch {
				val items = fetch.await()
				withContext(Dispatchers.Main) {
					if (!isAdded) return@withContext
					fillTentacleRow(playlistId, items)
					if (waitFor.remove(playlistId) && waitFor.isEmpty()) {
						view?.let { v -> androidx.core.view.OneShotPreDrawListener.add(v) { HomeTiming.mark("first rows filled") } }
						markContentReady()
					}
				}
			}
		}
		withContext(Dispatchers.Main) { markContentReady() }
	}

	/** One first-load Tentacle row's answer: its cards, or the row leaves when there are none. */
	private fun fillTentacleRow(playlistId: String, items: List<BaseItemDto>) {
		val rowAdapter = tentacleRowAdapters[playlistId] ?: return
		if (items.isNotEmpty()) {
			rowAdapter.replaceStaticItems(items)
			return
		}
		val rowsAdapter = adapter as? MutableObjectAdapter<Row> ?: return
		val listRow = (0 until rowsAdapter.size()).mapNotNull { rowsAdapter[it] as? ListRow }.firstOrNull { it.adapter === rowAdapter }
		if (listRow != null) rowsAdapter.remove(listRow)
		tentacleRowAdapters.remove(playlistId)
		currentRows = currentRows.filterNot { it is HomeFragmentTentacleRow && playlistId in it.playlistIds }.toMutableList()
		Timber.d("Tentacle row $playlistId is empty, left out")
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		
		verticalGridView?.apply {
			// Cache nearby rows to reduce view re-creation during vertical scroll.
			// Lower than the default to avoid excessive upfront binding on low-end devices.
			setItemViewCacheSize(6)
			
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
			// Hero content loading is kicked off early in onCreate (as soon as hero
			// config is confirmed enabled), so no need to call loadInitialContent here.
		} else {
			// Back from the player (or any other screen): Continue Watching, Next Up and the
			// other built-in rows load again only if something they show changed since they
			// last loaded (a playback stop, a favourite, a library change); the rest stay as
			// they are, without a request. The server's UserDataChanged message for a playback
			// arrives while the player is in front, when Home doesn't listen (RESUMED only),
			// so without this the row kept the old items until a later library change.
			refreshRows(force = false, delayed = true)
			if (tentacleDirty) {
				// A change without a version came while we were away — catch up now.
				tentacleDirty = false
				homeRefresher.request(HomeRefreshRequest(force = true))
			} else if (shownHomeVersion > 0 || homeVersionTracker.known.value > 0) {
				// Back in view (another screen, the player, the app back in front after sleep):
				// ask for the home version and refresh only if it moved (H2). An older plugin
				// has no version, and Home only refreshes on a change it heard of, as before.
				homeRefresher.request(HomeRefreshRequest())
			}
		}

		// Update audio queue — deferred to avoid calling commitNow() during an active fragment transaction.
		// isResumed guard prevents "Can not perform this action after onSaveInstanceState" if the
		// fragment navigates away before the handler fires.
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		view?.post { if (isResumed) nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>) }

		// Ensure focus is restored to the grid when returning from other screens (like search)
		// This prevents the issue where users can't control the media bar after backing out
		view?.postDelayed({
			if (isResumed && verticalGridView != null && !verticalGridView!!.hasFocus()) {
				verticalGridView?.requestFocus()
			}
		}, 100) // Small delay to let the fragment fully resume
	}

	/**
	 * Home is on screen again with the view it had (DestinationFragmentView hid it while a
	 * screen opened from it was on top): no selection event comes, so put the focus back on the
	 * selected card and its background, which the other screen replaced. Main thread.
	 */
	fun onShownAgain() {
		val grid = verticalGridView ?: return
		if (!grid.hasFocus()) grid.requestFocus()
		val item = currentItem
		val baseItem = item?.baseItem
		when {
			baseItem != null -> {
				val rowAdapter = currentRow?.adapter as? ItemRowAdapter
				if (rowAdapter?.queryType == QueryType.Views) backgroundService.setBackgroundFromLibrary(baseItem.id, BlurContext.BROWSING)
				else backgroundService.setBackground(baseItem, BlurContext.BROWSING)
			}
			// The hero has its own backdrop; its carousel goes on if the focus is back on it
			selectedPosition in 0 until adapter.size() && adapter[selectedPosition] is MediaBarRow -> {
				if (grid.hasFocus() && !mediaBarViewModel.isFocused.value) mediaBarViewModel.setFocused(true)
			}
			else -> backgroundService.clearBackgrounds()
		}
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

			// Collect the set of Tentacle-managed adapters so we can skip them.
			// Tentacle rows are refreshed separately by refreshTentacleRowsInPlace()
			// which fetches fresh data from the plugin — calling Retrieve() on them
			// concurrently causes races that can empty rows.
			val tentacleAdapters = tentacleRowAdapters.values.toSet()

			// Must run on Main thread: Retrieve()/ReRetrieveIfNeeded() may call loadStaticItems()
			// which adds items directly to the Leanback adapter (UI operation)
			// Also the built-in rows that left empty: nothing asked them again, so Continue
			// Watching never came back after a playback until the app restarted (#140).
			val shown = (0 until adapter.size()).map { adapter[it] }
			for (row in HiddenStockRows.toRecheck(shown, stockRows)) {
				val rowAdapter = (row as? ListRow)?.adapter as? ItemRowAdapter
				if (rowAdapter != null && rowAdapter in tentacleAdapters) continue
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}

			// Refresh playlists row
			playlistsRow?.refresh()
		}
	}

	/**
	 * Refresh Tentacle home screen when version changes — always in-place.
	 *
	 * 1. Refreshes hero/media bar items
	 * 2. Re-fetches sections list to detect structural changes
	 * 3. If structure unchanged: updates playlist items in-place (preserves scroll + selection)
	 * 4. If structure changed: removes old content rows, adds new ones to the adapter in-place
	 */
	/** A section's part in "did the home's structure change": for a row its playlist, shape and title. */
	private fun sectionKey(section: org.jellyfin.androidtv.data.repository.TentacleSection): String = when (section.type) {
		"row" -> "playlist:${section.playlistId}:${section.shape}:${section.displayText}"
		"builtin" -> "builtin:${section.sectionId}"
		else -> "unknown:${section.id}"
	}

	private suspend fun refreshTentacleRowsInPlace(fetched: TentacleSectionsResponse? = null) {
		if (!isAdded) return

		// Refresh hero/media bar — allowCache means it only tears down and reloads
		// if the hero content actually changed (no restart flicker on every event)
		mediaBarViewModel.loadInitialContent(allowCache = true)

		// Re-fetch sections to check for structural changes
		val sectionsResponse = fetched ?: withContext(Dispatchers.IO) { tentacleRepository.getSections() }
			?: return // Plugin unavailable, skip

		val newSections = sectionsResponse.sections.filter { it.type == "row" || it.type == "builtin" }
		val mergeCw = sectionsResponse.mergeContinueWatching
		val newKeys = newSections.map(::sectionKey) + "merge:$mergeCw"

		// A hero switched on after launch: its row is only attached at launch, when the hero
		// is already on, so it never appeared until the app restarted (#53). Attach it now; it
		// shows itself once the hero has loaded, and removes itself if the hero goes off.
		val heroOn = userSettingPreferences[UserSettingPreferences.mediaBarEnabled] &&
			sectionsResponse.sections.any { it.type == "hero" && !it.playlistId.isNullOrEmpty() }
		if (heroOn && !mediaBarRow.isAttached) {
			withContext(Dispatchers.Main) {
				val ctx = context
				val rowsAdapter = adapter as? MutableObjectAdapter<Row>
				if (isAdded && ctx != null && rowsAdapter != null) {
					Timber.i("Hero switched on: attaching the media bar")
					mediaBarRow.addToRowsAdapter(ctx, sharedCardPresenter, rowsAdapter)
					hasMediaBarAtPosition0 = true
				}
			}
		}

		val structureChanged = newKeys != currentTentacleSectionKeys

		// Also when the layout has no Tentacle playlist rows (only built-in ones, the default
		// layout): rebuilding re-created every built-in row on each library change, and an
		// empty Continue Watching / Next Up then stayed on screen as a header with no cards.
		if (!structureChanged) {
			// Structure unchanged — update playlist items in place.
			val limits = newSections
				.filter { it.type == "row" && !it.playlistId.isNullOrEmpty() }
				.associate { it.playlistId!! to it.maxItems }
			// Rows the layout lists but that are not on screen: left out earlier because their
			// fetch failed or came back empty. Nothing re-fetched them, so a row that timed out
			// once stayed missing until the app restarted (#49). Asked again on every refresh.
			val missing = limits.keys - tentacleRowAdapters.keys
			val (updates, returning) = kotlinx.coroutines.coroutineScope {
				val shown = tentacleRowAdapters.keys.map { playlistId ->
					async(Dispatchers.IO) { playlistId to tentacleRepository.fetchSectionItems(playlistId) }
				}
				val absent = missing.map { playlistId ->
					async(Dispatchers.IO) { tentacleRepository.fetchSectionItems(playlistId) }
				}
				shown.awaitAll() to absent.awaitAll().any { it.items.isNotEmpty() }
			}

			var rowLeft = false
			withContext(Dispatchers.Main) {
				for ((playlistId, fetch) in updates) {
					val rowAdapter = tentacleRowAdapters[playlistId] ?: continue
					val empties = if (fetch.source == SectionSource.SERVER && fetch.items.isEmpty()) {
						(emptyRowAnswers[playlistId] ?: 0) + 1
					} else 0
					emptyRowAnswers[playlistId] = empties
					val answerIds = fetch.items.map { it.id }
					val fromServer = fetch.source == SectionSource.SERVER && answerIds.isNotEmpty()
					val action = TentacleRowRefresh.decide(
						rowAdapter.size(), fetch, limits[playlistId] ?: 0, empties,
						sameShrunkAnswerAgain = fromServer && shrunkRowAnswers[playlistId] == answerIds,
					)
					if (action == RowRefreshAction.KEEP && fromServer) shrunkRowAnswers[playlistId] = answerIds
					else shrunkRowAnswers.remove(playlistId)
					when (action) {
						RowRefreshAction.REPLACE -> {
							rowAdapter.replaceStaticItems(fetch.items)
							Timber.d("Refreshed Tentacle row $playlistId with ${fetch.items.size} items")
						}
						RowRefreshAction.KEEP ->
							Timber.d("Kept Tentacle row $playlistId (${fetch.source}, ${fetch.items.size} vs ${rowAdapter.size()} shown)")
						RowRefreshAction.DROP -> {
							Timber.i("Tentacle row $playlistId is gone or empty")
							rowLeft = true
						}
					}
				}
				resyncSelectedItem()
			}
			if (!returning && !rowLeft) {
				shownHomeVersion = sectionsResponse.homeVersion
				Timber.i("Tentacle rows refreshed in-place (${updates.size} rows)")
				return
			}
			Timber.i("Tentacle rows: a row came back or left, rebuilding")
		}

		// Structure changed — rebuild content rows in-place
		Timber.i("Tentacle section structure changed, rebuilding rows in-place")

		// A layout change (a row moved, renamed or reshaped): the rows already on screen keep
		// their cards in the new layout, and only the new ones are fetched. Re-asking every row
		// (25 rows, 4 at a time, the plugin's row cache just cleared by the save) took 9-15 s
		// before Home showed a save. Their content is refreshed right after, below. Not when a
		// row came back or left (structure unchanged): that rebuild asks every row, as before.
		val shownItems: Map<String, List<BaseItemDto>> = keptRowItems(structureChanged, withContext(Dispatchers.Main) {
			if (!structureChanged) emptyMap() else tentacleRowAdapters.mapValues { (_, rowAdapter) ->
				(0 until rowAdapter.size()).mapNotNull { (rowAdapter.get(it) as? BaseRowItem)?.baseItem }
			}
		})

		// Pre-fetch items for the playlist rows not on screen, in parallel
		val tentacleRowData = kotlinx.coroutines.coroutineScope {
			newSections
				.filter { it.type == "row" && !it.playlistId.isNullOrEmpty() }
				.map { section ->
					async(Dispatchers.IO) {
						TentacleRowData(
							title = section.displayText,
							playlistId = section.playlistId!!,
							items = shownItems[section.playlistId] ?: tentacleRepository.getSectionItems(section.playlistId),
							wide = section.shape == "wide",
						)
					}
				}.awaitAll()
				.filter { it.items.isNotEmpty() }
		}
		val reusedRows = tentacleRowData.any { it.playlistId in shownItems }
		val tentacleMap = tentacleRowData.associateBy { it.playlistId }

		// Build new rows list
		val newRows = mutableListOf<HomeFragmentRow>()
		val homesections = userSettingPreferences.activeHomesections
		// As at the first load: a rebuild dropped the Live TV rows when this was a fixed false (M3).
		val includeLiveTvRows = liveTvRowsWanted && systemPreferences[SystemPreferences.liveTvRowsAvailable]
		val cachedViews = try { userViewsRepository.views.first() } catch (_: Exception) { null }

		val hasResumeRow = newSections.any { it.type == "builtin" && (it.sectionId == "resume" || it.sectionId == "resumevideo") }
		resetOnNow()
		for (section in newSections) {
			when (section.type) {
				"row" -> {
					val playlistId = section.playlistId ?: continue
					tentacleMap[playlistId]?.let { data ->
						newRows.add(HomeFragmentTentacleRow(listOf(data), tentacleRowAdapters))
					}
				}
				"builtin" -> {
					val sectionId = section.sectionId ?: continue
					// Merge on: Next Up folds into the Continue Watching row
					if (mergeCw && sectionId == "nextup" && hasResumeRow) continue
					addBuiltInSection(newRows, sectionId, includeLiveTvRows, cachedViews, mergeCw)
				}
			}
		}

		// Apply to adapter on main thread. Reuse existing ListRow objects where possible
		// to preserve horizontal scroll position within rows. Only create new Row objects
		// for genuinely new sections.
		if (!isAdded) return
		withContext(Dispatchers.Main) {
			if (!isAdded) return@withContext
			val rowsAdapter = (adapter as? MutableObjectAdapter<Row>) ?: return@withContext

			// Suppress selection callbacks during rebuild
			suppressSelectionClearing = true
			selectionDebouncer.cancel()
			backgroundDebouncer.cancel()

			// Index existing content rows for reuse: a Tentacle row by its playlist id, a
			// built-in row by its header. Matching Tentacle rows by title too gave one of two
			// rows that share a title the other's items (#54).
			val oldIdByAdapter: Map<Any, String> = tentacleRowAdapters.entries.associate { (id, a) -> (a as Any) to id }
			val existingById = mutableMapOf<String, ListRow>()
			val existingRowsByName = mutableMapOf<String, ListRow>()
			for (i in contentRowStartIndex until rowsAdapter.size()) {
				val row = rowsAdapter[i] as? ListRow ?: continue
				val id = row.adapter?.let { oldIdByAdapter[it] }
				if (id != null) existingById[id] = row
				else row.headerItem?.name?.let { existingRowsByName[it] = row }
			}

			// Build new content rows, reusing existing ListRow objects where possible.
			// For reused rows, update items silently via replaceStaticItems to preserve
			// the row's horizontal scroll position.
			hasMediaBarAtPosition0 = false

			// Build fresh rows into a temp adapter (also populates tentacleRowAdapters)
			tentacleRowAdapters.clear()
			onNowReused = false
			val presenterSelector = rowsAdapter.presenterSelector ?: return@withContext
			val ctx = context ?: return@withContext
			val tempAdapter = MutableObjectAdapter<Row>(presenterSelector)
			for (row in newRows) {
				row.addToRowsAdapter(ctx, sharedCardPresenter, tempAdapter)
			}
			val freshIdByAdapter: Map<Any, String> = tentacleRowAdapters.entries.associate { (id, a) -> (a as Any) to id }

			val newContentRows = mutableListOf<Row>()
			for (i in 0 until tempAdapter.size()) {
				val freshRow = tempAdapter[i] as? ListRow
				val name = freshRow?.headerItem?.name
				val freshId = freshRow?.adapter?.let { freshIdByAdapter[it] }
				val existingRow = when {
					// Same playlist, same title: reuse it. A renamed row is rebuilt so its new
					// header shows.
					freshId != null -> existingById[freshId]?.takeIf { it.headerItem?.name == name }
					name != null -> existingRowsByName[name]
					else -> null
				}

				if (existingRow != null && freshRow != null) {
					val existingAdapter = existingRow.adapter as? ItemRowAdapter
					val freshAdapter = freshRow.adapter as? ItemRowAdapter
					// A row's card shape (poster 2:3 vs wide 16:9) is bound to its presenter
					// when the ItemRowAdapter is built. Both shapes use one shared
					// CardPresenter instance each, so identity tells us if the shape changed.
					// Reusing the old ListRow would keep the old presenter and silently
					// discard the dashboard's shape change — it only ever "took" on rows
					// that had no existing match. Rebuild that one row instead; losing its
					// scroll position is correct when every card just changed size.
					val shapeChanged = freshAdapter != null && existingAdapter != null &&
						freshAdapter.cardPresenter !== existingAdapter.cardPresenter
					if (shapeChanged) {
						// Fresh row is already registered in tentacleRowAdapters by
						// addToRowsAdapter, so in-place item refreshes reach the new adapter.
						Timber.i("Tentacle row '$name' changed card shape, rebuilding it")
						newContentRows.add(freshRow)
					} else {
						// Reuse existing row as-is — preserves horizontal scroll position.
						// Don't touch the ItemRowAdapter's items here. Item content updates
						// are handled by the content-only refresh path (structureChanged=false).
						if (freshId != null && existingAdapter != null) {
							tentacleRowAdapters[freshId] = existingAdapter
						}
						// On Now kept on screen: its refills must reach the row shown, not the
						// fresh copy built for the comparison
						if (existingAdapter != null && freshAdapter != null && onNowAdapters[ON_NOW_ROW_KEY] === freshAdapter) {
							onNowAdapters[ON_NOW_ROW_KEY] = existingAdapter
							onNowReused = true
							Timber.w("On Now row kept on screen by the rebuild")
						}
						newContentRows.add(existingRow)
					}
				} else {
					// Genuinely new row — use as-is (already registered in tentacleRowAdapters)
					newContentRows.add(freshRow ?: continue)
				}
			}

			// Build full list and use replaceAll — existing rows matched by reference
			// dispatch notifyItemMoved, preserving both vertical and horizontal scroll.
			val prefixRows = (0 until contentRowStartIndex).mapNotNull { rowsAdapter[it] }
			// Preserve the media bar / hero row: it lives at contentRowStartIndex, is a
			// MediaBarRow (not a ListRow) and is not part of `newSections`, so it would
			// otherwise be dropped by the rebuild (hero disappears after a refresh).
			val mediaBarRowObj = (contentRowStartIndex until rowsAdapter.size())
				.map { rowsAdapter[it] }
				.filterIsInstance<MediaBarRow>()
				.firstOrNull()
			hasMediaBarAtPosition0 = mediaBarRowObj != null
			val fullList = prefixRows + listOfNotNull(mediaBarRowObj) + newContentRows

			// A row rebuilt for its new card shape is the same row to the diff (same playlist), so it
			// is changed in place (a crossfade) instead of removed and inserted, which left its place
			// blank for a moment while the focus was on it.
			fun playlistOf(row: Row): String? = (row as? ListRow)?.adapter?.let { oldIdByAdapter[it] ?: freshIdByAdapter[it] }
			rowsAdapter.replaceAll(
				fullList,
				areItemsTheSame = { old, new -> old === new || playlistOf(old)?.let { it == playlistOf(new) } == true },
				areContentsTheSame = { old, new -> old === new },
			)

			// The fresh rows were built into tempAdapter, so their loaders still point at it: an
			// empty answer (nothing to continue watching, no next up) removed the row from
			// tempAdapter and left its header on screen, taking D-pad focus with nothing in it.
			for (row in newContentRows) {
				((row as? ListRow)?.adapter as? ItemRowAdapter)?.setParent(rowsAdapter)
			}

			currentRows = newRows
			trackStockRows()
			resyncSelectedItem()
		}
		// The kept On Now row shows what the rebuild just read
		if (onNowReused) refreshOnNow()

		// Update tracked section keys
		currentTentacleSectionKeys = newKeys
		shownHomeVersion = sectionsResponse.homeVersion
		showingTentacleHome = true

		Timber.i("Tentacle rows rebuilt in-place (${newRows.size} content rows)")

		// The kept rows showed what they had: now their content (a new item limit or sort), in
		// place, with the layout already on screen. Structure unchanged, so no second rebuild
		// with kept cards.
		if (reusedRows) refreshTentacleRowsInPlace(sectionsResponse)
	}

	/** One home refresh (through [homeRefresher], or the first load's revalidation). */
	private suspend fun runHomeRefresh(request: HomeRefreshRequest) {
		if (!isAdded) return
		if (request.stockRows) refreshRows(force = true, delayed = false)
		if (!tentacleRepository.checkAvailable()) return

		val answer = tentacleRepository.fetchSections()
		when (answer) {
			is SectionsAnswer.On -> homeVersionTracker.noteVersion(answer.response.homeVersion)
			is SectionsAnswer.Off -> homeVersionTracker.noteVersion(answer.homeVersion)
			SectionsAnswer.Failed -> Unit
		}
		when (decideHomeRefresh(answer, shownHomeVersion, showingTentacleHome, request.force)) {
			HomeAnswerAction.APPLY -> refreshTentacleRowsInPlace((answer as SectionsAnswer.On).response)
			HomeAnswerAction.SKIP -> {
				if (answer is SectionsAnswer.Off) shownHomeVersion = answer.homeVersion
				Timber.d("Tentacle home unchanged (version $shownHomeVersion)")
			}
			HomeAnswerAction.SWITCH_TO_STOCK -> withContext(Dispatchers.Main) {
				// The home was switched off in Tentacle: rebuild Home, which then shows the stock
				// rows. "Off" used to look like "plugin missing" and changed nothing until a restart.
				if (isAdded && !isStateSaved) {
					Timber.i("Tentacle home switched off, showing the stock rows")
					parentFragmentManager.beginTransaction()
						.replace(R.id.rowsFragment, HomeRowsFragment())
						.commitNow()
				}
			}
			HomeAnswerAction.KEEP -> Timber.d("Tentacle home: no answer, keeping the rows")
		}
		refreshOnNow()
	}

	/** Rows about to be built: On Now is in this home only if its section comes up. */
	private fun resetOnNow() {
		onNowInHome = false
		onNowLeftOut = false
		onNowAdapters.clear()
	}

	/** On Now again (programmes end, time left moves on); the row leaves when nothing is on. */
	private suspend fun refreshOnNow() {
		if (!onNowInHome) return
		val rowAdapter = onNowAdapters[ON_NOW_ROW_KEY]
		val user = userRepository.currentUser.value ?: return
		val items = try {
			withContext(Dispatchers.IO) { loadOnNow(api, user.id) }
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (e: Exception) {
			Timber.w(e, "On Now refresh failed, keeping the row")
			return
		}
		withContext(Dispatchers.Main) {
			if (!isAdded || onNowAdapters[ON_NOW_ROW_KEY] !== rowAdapter) return@withContext
			if (rowAdapter == null) {
				// Left out (nothing was on, no favourites) and now there is something: build
				// Home again so the row takes its place, as a user switch does. Only then:
				// mid-rebuild the row exists but isn't registered yet.
				Timber.w("On Now refill: no row shown (left out: $onNowLeftOut), ${items.size} cards now")
				if (onNowLeftOut && items.isNotEmpty() && isResumed && !isStateSaved) {
					Timber.i("On Now has channels now, rebuilding Home")
					parentFragmentManager.beginTransaction()
						.replace(R.id.rowsFragment, HomeRowsFragment())
						.commitNow()
				}
				return@withContext
			}
			Timber.w("On Now refill: ${items.size} cards")
			if (items.isNotEmpty()) {
				rowAdapter.replaceStaticItems(items)
				return@withContext
			}
			val rowsAdapter = adapter as? MutableObjectAdapter<Row> ?: return@withContext
			val listRow = (0 until rowsAdapter.size()).mapNotNull { rowsAdapter[it] as? ListRow }.firstOrNull { it.adapter === rowAdapter }
			if (listRow != null) rowsAdapter.remove(listRow)
			onNowAdapters.remove(ON_NOW_ROW_KEY)
			currentRows = currentRows.filterNot { it is HomeFragmentTentacleRow && ON_NOW_ROW_KEY in it.playlistIds }.toMutableList()
		}
	}

	/**
	 * After an in-place row rebuild, re-emit state flows for the item at the
	 * current selection position. The old currentItem reference may be stale
	 * (e.g. row was replaced), so we read the actual item from the adapter.
	 */
	private fun resyncSelectedItem() {
		view?.postDelayed({
			// ALWAYS release suppression no matter what — if it stays on, all navigation breaks.
			if (!isAdded || adapter.size() == 0) {
				suppressSelectionClearing = false
				return@postDelayed
			}

			// Read the actual item at the current position from the adapter,
			// since currentItem may be stale after a structural rebuild.
			val pos = selectedPosition
			val row = if (pos in 0 until adapter.size()) adapter[pos] as? ListRow else null
			val rowAdapter = row?.adapter as? MutableObjectAdapter<*>
			val item = if (rowAdapter != null && rowAdapter.size() > 0) {
				rowAdapter[0] as? BaseRowItem
			} else null

			if (item != null) {
				currentItem = item
				_selectedPositionFlow.value = pos
				_selectedItemStateFlow.value = SelectedItemState(
					title = item.getName(requireContext()) ?: "",
					summary = item.getSummary(requireContext()) ?: "",
					baseItem = item.baseItem
				)
				backgroundService.setBackground(item.baseItem, BlurContext.BROWSING)
			}

			suppressSelectionClearing = false
		}, 500)
	}

	private suspend fun addBuiltInSection(
		rows: MutableList<HomeFragmentRow>,
		sectionId: String,
		includeLiveTvRows: Boolean,
		cachedViews: Collection<org.jellyfin.sdk.model.api.BaseItemDto>?,
		// Dashboard-controlled merge flag (from the Tentacle sections response).
		// null = fall back to the local device preference (non-Tentacle home).
		mergeContinueWatching: Boolean? = null,
	) {
		val mergeCw = mergeContinueWatching ?: userPreferences[UserPreferences.mergeContinueWatchingNextUp]
		when (sectionId) {
			"latestmedia" -> rows.add(helper.loadRecentlyAdded(cachedViews ?: userViewsRepository.views.first()))
			"recentlyreleased" -> rows.add(helper.loadRecentlyReleased())
			"smalllibrarytiles" -> rows.add(HomeFragmentViewsRow(small = false))
			"smalllibrarytiles_small", "librarybuttons" -> rows.add(HomeFragmentViewsRow(small = true))
			"resume", "resumevideo" -> {
				if (mergeCw) rows.add(helper.loadMergedContinueWatching())
				else rows.add(helper.loadResumeVideo())
			}
			"resumeaudio" -> rows.add(helper.loadResumeAudio())
			"activerecordings" -> rows.add(helper.loadLatestLiveTvRecordings())
			"nextup" -> {
				// Merge on: this is only reached when there's no resume row in the
				// sections (callers skip nextup when resume is present) — render
				// the merged row here so the user still gets their content.
				if (mergeCw) rows.add(helper.loadMergedContinueWatching())
				else rows.add(helper.loadNextUp())
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
			// Tentacle's own: what's on now on the person's favourite channels; no row when
			// there are none, nothing is on or they have no Live TV
			ON_NOW_SECTION_ID -> {
				onNowInHome = true
				val user = userRepository.currentUser.value
				if (user != null && user.policy?.enableLiveTvAccess != false) {
					val items = runCatching { withContext(Dispatchers.IO) { loadOnNow(api, user.id) } }
						.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; Timber.w(it, "On Now unavailable") }
						.getOrDefault(emptyList())
					onNowLeftOut = items.isEmpty()
					Timber.w("On Now: ${items.size} cards for this build")
					if (items.isNotEmpty()) {
						rows.add(HomeFragmentTentacleRow(
							listOf(TentacleRowData(getString(R.string.lbl_tentacle_on_now), ON_NOW_ROW_KEY, items, wide = true)),
							onNowAdapters,
						))
					}
				}
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
			// During row rebuild, Leanback fires selection callbacks for every row
			// being added/removed. Suppress ALL of them — both null items and valid
			// BaseRowItems — until resyncSelectedItem forces a proper re-selection.
			if (suppressSelectionClearing) return

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
