package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.constant.NavbarPosition
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.home.mediabar.MediaBarSlideshowViewModel
import org.jellyfin.androidtv.ui.home.mediabar.TrailerPreviewState
import org.jellyfin.androidtv.ui.home.mediabar.ExoPlayerTrailerView
import org.jellyfin.androidtv.ui.shared.toolbar.LeftSidebarNavigation
import org.jellyfin.androidtv.ui.shared.toolbar.Navbar
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import androidx.media3.datasource.HttpDataSource
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.TentacleNotification
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import org.jellyfin.androidtv.ui.base.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

class HomeFragment : Fragment() {
	companion object {
		// Track whether the loading overlay has been shown at least once this session.
		// Prevents the Tentacle logo from re-appearing when navigating back to Home.
		private var overlayShownOnce = false
	}

	private val mediaBarViewModel by inject<MediaBarSlideshowViewModel>()
	private val interactionTrackerViewModel by inject<InteractionTrackerViewModel>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userPreferences by inject<UserPreferences>()
	private val settingsViewModel by activityViewModel<SettingsViewModel>()
	private val tentacleRepository by inject<TentacleRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	private var titleView: TextView? = null
	private var logoView: ImageView? = null
	private var infoRowView: SimpleInfoRowView? = null
	private var summaryView: TextView? = null
	private var backgroundImage: ImageView? = null
	private var trailerWebView: ComposeView? = null
	private var rowsFragment: HomeRowsFragment? = null
	private var muteButton: ImageButton? = null
	private var loadingOverlay: View? = null
	private val _isTrailerMuted = kotlinx.coroutines.flow.MutableStateFlow(false)
	private var snowfallView: SnowfallView? = null
	private var petalfallView: PetalfallView? = null
	private var leaffallView: LeaffallView? = null
	private var summerView: SummerView? = null
	private var halloweenView: HalloweenView? = null

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		val view = inflater.inflate(R.layout.fragment_home, container, false)

		titleView = view.findViewById(R.id.title)
		logoView = view.findViewById(R.id.logo)
		infoRowView = view.findViewById(R.id.infoRow)
		summaryView = view.findViewById(R.id.summary)
		backgroundImage = view.findViewById(R.id.backgroundImage)
		trailerWebView = view.findViewById(R.id.trailerWebView)
		snowfallView = view.findViewById(R.id.snowfallView)
		petalfallView = view.findViewById(R.id.petalfallView)
		leaffallView = view.findViewById(R.id.leaffallView)
		summerView = view.findViewById(R.id.summerView)
		halloweenView = view.findViewById(R.id.halloweenView)
		muteButton = view.findViewById(R.id.muteButton)
		loadingOverlay = view.findViewById(R.id.loadingOverlay)

		// Only show loading overlay on first visit — hide immediately on subsequent navigations
		if (overlayShownOnce) {
			loadingOverlay?.isVisible = false
		}

		// Initialize mute state from preference
		_isTrailerMuted.value = !userSettingPreferences[UserSettingPreferences.previewAudioEnabled]
		updateMuteButtonIcon()
		muteButton?.setOnClickListener {
			_isTrailerMuted.value = !_isTrailerMuted.value
			userSettingPreferences[UserSettingPreferences.previewAudioEnabled] = !_isTrailerMuted.value
			updateMuteButtonIcon()
		}

		setupNavbar(view)

		return view
	}

	private fun setupNavbar(view: View) {
		val navbarPosition = userPreferences[UserPreferences.navbarPosition] ?: NavbarPosition.TOP
		
		when (navbarPosition) {
			NavbarPosition.LEFT -> {
				val toolbarContainer = view.findViewById<FrameLayout>(R.id.toolbar_actions)
				toolbarContainer.isVisible = false
				
				val sidebarContainer = view.findViewById<FrameLayout>(R.id.left_sidebar)
				sidebarContainer.isVisible = true
				
				val sidebarView = view.findViewById<ComposeView>(R.id.sidebar)
				sidebarView.setContent {
					LeftSidebarNavigation(
						activeButton = NavbarActiveButton.Home
					)
				}
			}
			NavbarPosition.TOP -> {
				val sidebarContainer = view.findViewById<FrameLayout>(R.id.left_sidebar)
				sidebarContainer.isVisible = false
				
				val toolbarContainer = view.findViewById<FrameLayout>(R.id.toolbar_actions)
				toolbarContainer.isVisible = true
				
				val toolbarView = view.findViewById<ComposeView>(R.id.toolbar)
				toolbarView.setContent {
					Navbar(
						activeButton = NavbarActiveButton.Home
					)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		setupSeasonalSurprise()
		setupNotificationToast(view)
		startNotificationPolling()

		settingsViewModel.settingsClosedCounter
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach {
				setupSeasonalSurprise()
				view?.let { setupNavbar(it) }
			}
			.launchIn(lifecycleScope)

		rowsFragment = childFragmentManager.findFragmentById(R.id.rowsFragment) as? HomeRowsFragment

		// Dismiss loading overlay once rows are rendered and hero is resolved.
		// Keeps the Tentacle logo visible during loading so users see a polished
		// transition instead of an empty home screen that pops in piece by piece.
		val fragment = rowsFragment
		if (fragment != null) {
			fun dismissOverlay() {
				val overlay = loadingOverlay ?: return
				if (!overlay.isVisible) return
				overlayShownOnce = true
				overlay.animate()
					.alpha(0f)
					.setDuration(400)
					.withEndAction { overlay.isVisible = false }
					.start()
			}

			kotlinx.coroutines.flow.combine(
				fragment.contentReady,
				mediaBarViewModel.state,
			) { rowsReady, heroState ->
				if (!rowsReady) return@combine false
				// If hero is part of the layout, wait for it to finish loading
				if (fragment.hasMediaBarAtPosition0) {
					heroState !is org.jellyfin.androidtv.ui.home.mediabar.MediaBarState.Loading
				} else {
					true // No hero — rows ready is enough
				}
			}
				.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
				.onEach { allReady -> if (allReady) dismissOverlay() }
				.launchIn(lifecycleScope)

			// Safety timeout — dismiss after 8s no matter what
			lifecycleScope.launch {
				delay(8000)
				dismissOverlay()
			}
		}

		rowsFragment?.selectedItemStateFlow
			?.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			?.onEach { state ->
				titleView?.text = state.title
				summaryView?.text = state.summary
				infoRowView?.setItem(state.baseItem)
			}
			?.launchIn(lifecycleScope)

		rowsFragment?.selectedPositionFlow
			?.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			?.onEach { position ->
				updateMediaBarBackground()
			}
			?.launchIn(lifecycleScope)

		kotlinx.coroutines.flow.combine(
			mediaBarViewModel.state,
			mediaBarViewModel.isFocused,
			mediaBarViewModel.playbackState,
		) { _, _, _ -> Unit }
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { updateMediaBarBackground() }
			.launchIn(lifecycleScope)

		trailerWebView?.setContent {
			val trailerState by mediaBarViewModel.trailerState.collectAsState()
			val isMuted by _isTrailerMuted.collectAsState()
			val httpDataSourceFactory = koinInject<HttpDataSource.Factory>()

			val activeInfo = when (val state = trailerState) {
				is TrailerPreviewState.Buffering -> state.info
				is TrailerPreviewState.Playing -> state.info
				else -> null
			}
			val showTrailer = trailerState is TrailerPreviewState.Playing

			if (activeInfo?.streamInfo != null) {
				key(activeInfo.previewKey, isMuted) {
					ExoPlayerTrailerView(
						streamInfo = activeInfo.streamInfo,
						startSeconds = activeInfo.startSeconds,
						segments = activeInfo.segments,
						muted = isMuted,
						isVisible = showTrailer,
						onVideoEnded = { mediaBarViewModel.onTrailerEnded() },
						onVideoReady = { mediaBarViewModel.onTrailerReady() },
						dataSourceFactory = if (activeInfo.isLocal) httpDataSourceFactory else null,
					)
				}
			}
		}

		mediaBarViewModel.trailerState
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { trailerState ->
				val hasTrailer = trailerState is TrailerPreviewState.Buffering ||
					trailerState is TrailerPreviewState.Playing
				trailerWebView?.isVisible = hasTrailer && shouldShowMediaBar()
				muteButton?.isVisible = trailerState is TrailerPreviewState.Playing && shouldShowMediaBar()
			}
			.launchIn(lifecycleScope)

		// Stop trailers when the in-app screensaver activates
		interactionTrackerViewModel.visible
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { screensaverVisible ->
				if (screensaverVisible) {
					mediaBarViewModel.stopTrailer()
				} else {
					mediaBarViewModel.restartTrailerForCurrentSlide()
				}
			}
			.launchIn(lifecycleScope)
	}

	private fun updateMediaBarBackground() {
		val state = mediaBarViewModel.state.value
		val shouldShowMediaBar = shouldShowMediaBar()

		if (state is org.jellyfin.androidtv.ui.home.mediabar.MediaBarState.Ready && shouldShowMediaBar) {
			val playbackState = mediaBarViewModel.playbackState.value
			val currentItem = state.items.getOrNull(playbackState.currentIndex)
			val backdropUrl = currentItem?.backdropUrl
			val logoUrl = currentItem?.logoUrl

			if (backdropUrl != null) {
				backgroundImage?.isVisible = true
				backgroundImage?.load(backdropUrl) {
					crossfade(400)
				}
			} else {
				backgroundImage?.isVisible = false
			}

			if (logoUrl != null) {
				logoView?.isVisible = true
				logoView?.load(logoUrl) {
					crossfade(300)
				}
			} else {
				logoView?.isVisible = false
			}

			titleView?.isVisible = false
			infoRowView?.isVisible = false
			summaryView?.isVisible = false
		} else {
			// Ensure trailer overlay cannot linger when media bar is not active.
			mediaBarViewModel.stopTrailer()
			trailerWebView?.isVisible = false

			backgroundImage?.isVisible = false
			logoView?.isVisible = false
			titleView?.isVisible = true
			infoRowView?.isVisible = true
			summaryView?.isVisible = true
		}
	}

	private fun updateMuteButtonIcon() {
		muteButton?.setImageResource(
			if (_isTrailerMuted.value) R.drawable.ic_volume_off else R.drawable.ic_volume_on
		)
		muteButton?.contentDescription = getString(
			if (_isTrailerMuted.value) R.string.lbl_unmute else R.string.lbl_mute
		)
	}

	private fun shouldShowMediaBar(): Boolean {
		val isFocused = mediaBarViewModel.isFocused.value
		val selectedPosition = rowsFragment?.selectedPositionFlow?.value ?: -1
		val hasMediaBar = rowsFragment?.hasMediaBarAtPosition0 == true
		val isMediaBarEnabled = userSettingPreferences[UserSettingPreferences.mediaBarEnabled]
		return isMediaBarEnabled && (isFocused || (selectedPosition == 0 && hasMediaBar))
	}

	private fun startNotificationPolling() {
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				while (true) {
					delay(15_000)
					try {
						tentacleRepository.pollNotifications()
					} catch (_: Exception) {}
				}
			}
		}
	}

	private fun setupNotificationToast(view: View) {
		val toastView = view.findViewById<ComposeView>(R.id.notificationToast)
		toastView.setContent {
			val notifications by tentacleRepository.pendingNotifications.collectAsState()
			val currentNotif = notifications.firstOrNull()

			var visible by remember { mutableStateOf(false) }

			LaunchedEffect(currentNotif?.id) {
				if (currentNotif != null) {
					visible = true
					delay(8000)
					visible = false
					delay(400) // Wait for exit animation
					tentacleRepository.dismissNotification(currentNotif.id)
					tentacleRepository.consumeNotification(currentNotif.id)
				}
			}

			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
				AnimatedVisibility(
					visible = visible && currentNotif != null,
					enter = slideInHorizontally { it },
					exit = slideOutHorizontally { it },
				) {
					if (currentNotif != null) {
						NotificationToast(
							notification = currentNotif,
							onDismiss = {
								visible = false
								lifecycleScope.launch {
									delay(400)
									tentacleRepository.dismissNotification(currentNotif.id)
									tentacleRepository.consumeNotification(currentNotif.id)
								}
							},
							onClick = {
								visible = false
								lifecycleScope.launch {
									delay(400)
									tentacleRepository.dismissNotification(currentNotif.id)
									tentacleRepository.consumeNotification(currentNotif.id)
									if (!currentNotif.jellyfinItemId.isNullOrEmpty()) {
										try {
											val itemUuid = java.util.UUID.fromString(currentNotif.jellyfinItemId)
											navigationRepository.navigate(Destinations.itemDetails(itemUuid))
										} catch (_: IllegalArgumentException) {}
									}
								}
							},
						)
					}
				}
			}
		}
	}

	/**
	 * Setup the seasonal surprise effects based on user selection.
	 * Options: none, winter (❄️), spring (🌸🌼), summer (☀️🏐), fall (🍁🍂)
	 */
	private fun setupSeasonalSurprise() {
		val selection = userPreferences[UserPreferences.seasonalSurprise]

		snowfallView?.isVisible = false
		snowfallView?.stopSnowing()
		petalfallView?.isVisible = false
		petalfallView?.stopFalling()
		leaffallView?.isVisible = false
		leaffallView?.stopFalling()
		summerView?.isVisible = false
		summerView?.stopEffect()
		halloweenView?.isVisible = false
		halloweenView?.stopEffect()
		
		when (selection) {
			"winter" -> {
				snowfallView?.isVisible = true
				snowfallView?.startSnowing()
			}
			"spring" -> {
				petalfallView?.isVisible = true
				petalfallView?.startFalling()
			}
			"summer" -> {
				summerView?.isVisible = true
				summerView?.startEffect()
			}
			"halloween" -> {
				halloweenView?.isVisible = true
				halloweenView?.startEffect()
			}
			"fall" -> {
				leaffallView?.isVisible = true
				leaffallView?.startFalling()
			}
			// "none" or any other value - no effect
		}
	}

	override fun onPause() {
		super.onPause()
		mediaBarViewModel.stopTrailer()
	}

	override fun onResume() {
		super.onResume()
		mediaBarViewModel.restartTrailerForCurrentSlide()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		snowfallView?.stopSnowing()
		petalfallView?.stopFalling()
		leaffallView?.stopFalling()
		summerView?.stopEffect()
		halloweenView?.stopEffect()
		titleView = null
		logoView = null
		summaryView = null
		infoRowView = null
		backgroundImage = null
		trailerWebView = null
		rowsFragment = null
		snowfallView = null
		petalfallView = null
		leaffallView = null
		summerView = null
		halloweenView = null
	}
}

@androidx.compose.runtime.Composable
private fun NotificationToast(
	notification: TentacleNotification,
	onDismiss: () -> Unit,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.padding(24.dp)
			.widthIn(max = 380.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(
				Brush.horizontalGradient(
					colors = listOf(Color(0xFF1a1a2e), Color(0xFF16213e))
				)
			)
			.clickable(onClick = onClick)
			.padding(12.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (!notification.posterPath.isNullOrEmpty()) {
			AsyncImage(
				model = "https://image.tmdb.org/t/p/w185${notification.posterPath}",
				contentDescription = null,
				modifier = Modifier
					.size(width = 50.dp, height = 75.dp)
					.clip(RoundedCornerShape(6.dp)),
				contentScale = ContentScale.Crop,
			)
		}

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = "Ready to Watch",
				color = Color(0xFF8b5cf6),
				fontSize = 11.sp,
				fontWeight = FontWeight.Bold,
				letterSpacing = 0.5.sp,
			)
			Text(
				text = notification.message,
				color = Color.White,
				fontSize = 14.sp,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Text(
			text = "\u2715",
			color = Color.White.copy(alpha = 0.6f),
			fontSize = 16.sp,
			modifier = Modifier
				.clickable(onClick = onDismiss)
				.padding(4.dp),
		)
	}
}
