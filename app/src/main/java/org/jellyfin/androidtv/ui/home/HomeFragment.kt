package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.fragment.app.Fragment
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.MediaHubRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject

class HomeFragment : Fragment() {
	private val sessionRepository by inject<SessionRepository>()
	private val serverRepository by inject<ServerRepository>()
	private val notificationRepository by inject<NotificationsRepository>()
	private val mediaHubRepository by inject<MediaHubRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val api by inject<ApiClient>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val rowsFocusRequester = remember { FocusRequester() }
		val heroButtonFocusRequester = remember { FocusRequester() }

		// null = loading, empty = no hero, non-empty = has hero
		var heroItems by remember { mutableStateOf<List<BaseItemDto>?>(null) }
		var heroExpanded by remember { mutableStateOf(true) }
		var hasNavigatedToRows by remember { mutableStateOf(false) }

		LaunchedEffect(Unit) {
			heroItems = withContext(Dispatchers.IO) {
				if (mediaHubRepository.checkAvailable()) {
					mediaHubRepository.getHeroItems()
				} else {
					emptyList()
				}
			}
		}

		val heroVisible = heroExpanded && (heroItems?.isNotEmpty() == true)
		// Mount rows once hero collapses or if there are no hero items; keep mounted forever after
		val rowsShouldMount = heroItems != null && (!heroVisible || hasNavigatedToRows)

		// Focus rows when hero collapses
		LaunchedEffect(heroVisible, heroItems) {
			if (heroItems != null && !heroVisible) {
				delay(150) // let fragment initialize on first mount
				rowsFocusRequester.requestFocus()
			}
		}

		// Focus hero button when returning from rows
		LaunchedEffect(heroVisible, hasNavigatedToRows) {
			if (heroVisible && hasNavigatedToRows) {
				heroButtonFocusRequester.requestFocus()
			}
		}

		// If no hero items at all, mark as navigated so rows mount immediately
		LaunchedEffect(heroItems) {
			if (heroItems != null && heroItems!!.isEmpty()) {
				hasNavigatedToRows = true
			}
		}

		JellyfinTheme {
			Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
				// Rows layer — mounted after first hero collapse, stays alive forever
				if (rowsShouldMount) {
					Column(modifier = Modifier.fillMaxSize()) {
						Spacer(modifier = Modifier.height(95.dp))

						var rowsSupportFragment by remember { mutableStateOf<HomeRowsFragment?>(null) }
						AndroidFragment<HomeRowsFragment>(
							modifier = Modifier
								.weight(1f)
								.fillMaxWidth()
								.focusGroup()
								.focusRequester(rowsFocusRequester)
								.focusProperties {
									onExit = {
										val isFirstRowSelected = rowsSupportFragment?.selectedPosition?.let { it <= 0 } ?: false
										if (requestedFocusDirection == FocusDirection.Up && isFirstRowSelected && heroItems?.isNotEmpty() == true) {
											// Go back to hero
											cancelFocusChange()
											heroExpanded = true
											rowsSupportFragment?.selectedPosition = 0
											rowsSupportFragment?.verticalGridView?.clearFocus()
										} else if (requestedFocusDirection == FocusDirection.Up && isFirstRowSelected) {
											// No hero items, allow exit to toolbar
											rowsSupportFragment?.selectedPosition = 0
											rowsSupportFragment?.verticalGridView?.clearFocus()
										} else if (requestedFocusDirection != FocusDirection.Up || !isFirstRowSelected) {
											cancelFocusChange()
										}
									}
								},
							onUpdate = { fragment ->
								rowsSupportFragment = fragment
							}
						)
					}
				}

				// Hero overlay with smooth animated transition
				AnimatedVisibility(
					visible = heroVisible,
					enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
						initialOffsetY = { -it / 4 },
						animationSpec = tween(300),
					),
					exit = fadeOut(animationSpec = tween(300)) + slideOutVertically(
						targetOffsetY = { -it / 4 },
						animationSpec = tween(300),
					),
				) {
					HeroSpotlight(
						items = heroItems ?: emptyList(),
						api = api,
						onItemSelected = { item ->
							navigationRepository.navigate(Destinations.itemDetails(item.id))
						},
						onScrollDown = {
							heroExpanded = false
							hasNavigatedToRows = true
						},
						buttonFocusRequester = heroButtonFocusRequester,
						modifier = Modifier.fillMaxSize(),
					)
				}

				// Toolbar — transparent, always on top
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.align(Alignment.TopStart)
						.zIndex(1f),
				) {
					MainToolbar(MainToolbarActiveButton.Home)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		sessionRepository.currentSession
			.flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
			.map { session ->
				if (session == null) null
				else serverRepository.getServer(session.serverId)
			}
			.onEach { server ->
				notificationRepository.updateServerNotifications(server)
			}
			.launchIn(viewLifecycleOwner.lifecycleScope)
	}
}
