package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.model.AggregatedLibrary
import org.jellyfin.androidtv.data.repository.MultiServerRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.androidtv.ui.NowPlayingComposable
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.button.IconButtonDefaults
import org.jellyfin.androidtv.ui.base.focusBorderColor
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.androidtv.ui.settings.compat.SettingsViewModel
import org.jellyfin.androidtv.ui.shuffle.ShuffleManager
import org.jellyfin.androidtv.ui.shuffle.ShuffleOptionsDialog
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.ToolbarButton
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinActivityViewModel
import timber.log.Timber
import java.util.UUID

enum class NavbarActiveButton {
	User,
	Home,
	Library,
	Search,
	Discover,
	Activity,

	None,
}


@Composable
fun Navbar(
	activeButton: NavbarActiveButton = NavbarActiveButton.None,
	activeLibraryId: UUID? = null,
) {
	val context = LocalContext.current
	val userRepository = koinInject<UserRepository>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val settingsClosedCounter by settingsViewModel.settingsClosedCounter.collectAsState()
	val api = koinInject<ApiClient>()
	val userViewsRepository = koinInject<UserViewsRepository>()
	val multiServerRepository = koinInject<org.jellyfin.androidtv.data.repository.MultiServerRepository>()
	val sessionRepository = koinInject<org.jellyfin.androidtv.auth.repository.SessionRepository>()
	val tentacleRepository = koinInject<TentacleRepository>()
	val activityDownloadCount by tentacleRepository.activityDownloadCount.collectAsState()

	// Fetch toolbar config from Tentacle plugin (re-fetches when toolbarRefreshKey changes)
	var toolbarButtons by remember { mutableStateOf<List<ToolbarButton>>(emptyList()) }
	var toolbarRefreshKey by remember { mutableIntStateOf(0) }
	LaunchedEffect(toolbarRefreshKey) {
		toolbarButtons = tentacleRepository.getToolbarConfig()
	}

	// Background polling so badge is populated even before visiting the Activity tab.
	// Polls every 10s while downloads are active (so badge clears promptly), 30s when idle.
	// Pauses when the app is not in the foreground.
	val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
	LaunchedEffect(lifecycleOwner) {
		lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			while (true) {
				try {
					tentacleRepository.getActivity()
				} catch (_: Exception) {}
				val delay = if (tentacleRepository.activityDownloadCount.value > 0) 10_000L else 30_000L
				kotlinx.coroutines.delay(delay)
			}
		}
	}

	// React immediately when Jellyfin library changes — refresh activity badge + toolbar config
	LaunchedEffect(api) {
		try {
			api.webSocket.subscribe<LibraryChangedMessage>().collect { message ->
				if (message.data?.itemsAdded?.isNotEmpty() == true) {
					try { tentacleRepository.getActivity() } catch (_: Exception) {}
				}
				// Re-fetch toolbar config in case it changed
				toolbarRefreshKey++
			}
		} catch (_: Exception) {}
	}

	val userPreferences = koinInject<UserPreferences>()
	val imageLoader = koinInject<coil3.ImageLoader>()

	// Prevent user image to disappear when signing out by skipping null values
	val currentUser by remember { userRepository.currentUser.filterNotNull() }.collectAsState(null)
	val userImage = remember(currentUser) { currentUser?.primaryImage?.getUrl(api) }

	// Preload user image into cache as soon as we have the URL
	LaunchedEffect(userImage) {
		if (userImage != null) {
			withContext(Dispatchers.IO) {
				val request = coil3.request.ImageRequest.Builder(context)
					.data(userImage)
					.memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
					.diskCachePolicy(coil3.request.CachePolicy.ENABLED)
					.build()
				imageLoader.execute(request)
			}
		}
	}

	// Load preferences that are still local (not controlled by Tentacle toolbar config)
	var enableMultiServer by remember { mutableStateOf(false) }
	var shuffleContentType by remember { mutableStateOf("both") }
	var clockBehavior by remember { mutableStateOf(ClockBehavior.ALWAYS) }
	LaunchedEffect(settingsClosedCounter) {
		enableMultiServer = userPreferences[UserPreferences.enableMultiServerLibraries] ?: false
		shuffleContentType = userPreferences[UserPreferences.shuffleContentType] ?: "both"
		clockBehavior = userPreferences[UserPreferences.clockBehavior]
	}

	// Load user views/libraries
	var userViews by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
	LaunchedEffect(Unit) {
		userViewsRepository.views.collect { views ->
			userViews = views.toList()
		}
	}

	// Load aggregated libraries from all servers
	val aggregationScope = rememberCoroutineScope()
	var aggregatedLibraries by remember { mutableStateOf<List<org.jellyfin.androidtv.data.model.AggregatedLibrary>>(emptyList()) }
	LaunchedEffect(enableMultiServer) {
		if (enableMultiServer) {
			aggregationScope.launch(Dispatchers.IO) {
				try {
					aggregatedLibraries = multiServerRepository.getAggregatedLibraries()
				} catch (e: Exception) {
				}
			}
		}
	}

	// Track current session for server switching
	val currentSession by sessionRepository.currentSession.collectAsState()

	Navbar(
		userImage = userImage,
		activeButton = activeButton,
		activeLibraryId = activeLibraryId,
		userViews = userViews,
		aggregatedLibraries = aggregatedLibraries,
		enableMultiServer = enableMultiServer,
		currentSession = currentSession,
		shuffleContentType = shuffleContentType,
		clockBehavior = clockBehavior,
		activityDownloadCount = activityDownloadCount,
		toolbarButtons = toolbarButtons,
	)
}

@Composable
private fun Navbar(
	userImage: String? = null,
	activeButton: NavbarActiveButton,
	activeLibraryId: UUID? = null,
	userViews: List<BaseItemDto> = emptyList(),
	aggregatedLibraries: List<org.jellyfin.androidtv.data.model.AggregatedLibrary> = emptyList(),
	enableMultiServer: Boolean = false,
	currentSession: Session? = null,
	shuffleContentType: String = "both",
	clockBehavior: ClockBehavior = ClockBehavior.ALWAYS,
	activityDownloadCount: Int = 0,
	toolbarButtons: List<ToolbarButton> = emptyList(),
) {
	val focusRequester = remember { FocusRequester() }
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	val navigationRepository = koinInject<NavigationRepository>()
	val mediaManager = koinInject<MediaManager>()
	val sessionRepository = koinInject<SessionRepository>()
	val itemLauncher = koinInject<ItemLauncher>()
	val api = koinInject<ApiClient>()
	val apiClientFactory = koinInject<ApiClientFactory>()
	val shuffleManager = koinInject<ShuffleManager>()
	val settingsViewModel = koinActivityViewModel<SettingsViewModel>()
	val activity = LocalActivity.current
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	var showShuffleDialog by remember { mutableStateOf(false) }
	val isShuffling by shuffleManager.isShuffling.collectAsState()

	val focusColor = focusBorderColor()
	val focusContentColor = if (focusColor.luminance() > 0.4f) Color(0xFF444444) else Color(0xFFDDDDDD)

	val activeButtonColors = ButtonDefaults.colors(
		containerColor = JellyfinTheme.colorScheme.buttonActive,
		contentColor = JellyfinTheme.colorScheme.onButtonActive,
	)

	val toolbarButtonColors = ButtonDefaults.colors(
		containerColor = Color.Transparent,
		contentColor = JellyfinTheme.colorScheme.onButton,
		focusedContainerColor = focusColor,
		focusedContentColor = focusContentColor,
	)

	// Get overlay preferences for toolbar styling
	val overlayOpacity = userSettingPreferences[UserSettingPreferences.mediaBarOverlayOpacity] / 100f
	val overlayColor = when (userSettingPreferences[UserSettingPreferences.mediaBarOverlayColor]) {
		"black" -> Color.Black
		"dark_blue" -> Color(0xFF1A2332)
		"purple" -> Color(0xFF4A148C)
		"teal" -> Color(0xFF00695C)
		"navy" -> Color(0xFF0D1B2A)
		"charcoal" -> Color(0xFF36454F)
		"brown" -> Color(0xFF3E2723)
		"dark_red" -> Color(0xFF8B0000)
		"dark_green" -> Color(0xFF0B4F0F)
		"slate" -> Color(0xFF475569)
		"indigo" -> Color(0xFF1E3A8A)
		else -> Color.Gray
	}

	Toolbar(
		modifier = Modifier
			.focusRestorer(focusRequester)
			.focusGroup(),
		start = {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				val userImagePainter = userImage?.let { rememberAsyncImagePainter(it) }
				val userImageState by userImagePainter?.state?.collectAsState()
					?: remember { mutableStateOf<AsyncImagePainter.State?>(null) }
				val userImageVisible = userImagePainter != null && userImageState is AsyncImagePainter.State.Success

				val interactionSource = remember { MutableInteractionSource() }
				val isFocused by interactionSource.collectIsFocusedAsState()
				val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "UserAvatarFocusScale")

				IconButton(
					onClick = {
						if (activeButton != NavbarActiveButton.User) {
							mediaManager.clearAudioQueue()
							sessionRepository.destroyCurrentSession()

							// Open login activity
							activity?.startActivity(ActivityDestinations.startup(activity))
							activity?.finishAfterTransition()
						}
					},
					colors = if (userImageVisible) {
						ButtonDefaults.colors(
							containerColor = Color.Transparent,
							contentColor = JellyfinTheme.colorScheme.onButton,
							focusedContainerColor = focusColor,
							focusedContentColor = focusContentColor,
						)
					} else {
						toolbarButtonColors
					},
					contentPadding = if (userImageVisible) PaddingValues(0.dp) else IconButtonDefaults.ContentPadding,
					interactionSource = interactionSource,
					modifier = Modifier.scale(scale),
				) {
					if (!userImageVisible) {
						Icon(
							imageVector = ImageVector.vectorResource(R.drawable.ic_user),
							contentDescription = stringResource(R.string.lbl_switch_user),
						)
					} else {
						Image(
							painter = requireNotNull(userImagePainter),
							contentDescription = stringResource(R.string.lbl_switch_user),
							contentScale = ContentScale.Crop,
							modifier = Modifier
								.aspectRatio(1f)
								.border(
									width = if (isFocused) 2.dp else 0.dp,
									color = if (isFocused) focusColor else Color.Transparent,
									shape = IconButtonDefaults.Shape
								)
								.clip(IconButtonDefaults.Shape)
						)
					}
				}

				ToolbarButtons(
					backgroundColor = overlayColor,
					alpha = overlayOpacity
				) {
					NowPlayingComposable(
						onFocusableChange = {},
					)
				}
			}
		},
		center = {
			// Determine button order: use toolbar config order for the 5 configurable buttons,
			// interleaved with local-only buttons in fixed positions
			val configuredIds = if (toolbarButtons.isNotEmpty()) {
				toolbarButtons.filter { it.enabled }.map { it.id }
			} else {
				listOf("search", "discover", "activity", "favorites", "libraries")
			}

			ToolbarButtons(
				modifier = Modifier
					.focusRequester(focusRequester),
				backgroundColor = overlayColor,
				alpha = overlayOpacity
			) {
				// Home is always first
				ExpandableIconButton(
					icon = ImageVector.vectorResource(R.drawable.ic_house),
					label = stringResource(R.string.lbl_home),
					onClick = {
						navigationRepository.reset(Destinations.home)
					},
					colors = toolbarButtonColors,
				)

				// Render all buttons from Tentacle toolbar config order
				for (btnId in configuredIds) {
					when (btnId) {
						"search" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_search),
							label = stringResource(R.string.lbl_search),
							onClick = {
								navigationRepository.navigate(Destinations.search())
							},
							colors = toolbarButtonColors,
						)
						"discover" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_compass),
							label = stringResource(R.string.lbl_add_media),
							onClick = {
								navigationRepository.navigate(Destinations.tentacleDiscover)
							},
							colors = toolbarButtonColors,
						)
						"activity" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_info_circle),
							label = stringResource(R.string.lbl_downloads),
							onClick = {
								navigationRepository.navigate(Destinations.tentacleActivity)
							},
							colors = toolbarButtonColors,
							badgeCount = activityDownloadCount,
						)
						"favorites" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_heart),
							label = stringResource(R.string.lbl_favorites),
							onClick = {
								navigationRepository.navigate(Destinations.allFavorites)
							},
							colors = toolbarButtonColors,
						)
						"libraries" -> ExpandableLibrariesButton(
							activeLibraryId = activeLibraryId,
							userViews = userViews,
							aggregatedLibraries = aggregatedLibraries,
							enableMultiServer = enableMultiServer,
							currentSession = currentSession,
							colors = toolbarButtonColors,
							activeColors = activeButtonColors,
							navigationRepository = navigationRepository,
							itemLauncher = itemLauncher,
						)
						"shuffle" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_shuffle),
							label = if (isShuffling) "..." else stringResource(R.string.lbl_shuffle),
							onClick = {
								if (!isShuffling) {
									kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
										shuffleManager.quickShuffle(context)
									}
								}
							},
							onLongClick = { showShuffleDialog = true },
							colors = toolbarButtonColors,
						)
						"genres" -> ExpandableIconButton(
							icon = ImageVector.vectorResource(R.drawable.ic_masks),
							label = stringResource(R.string.lbl_genres),
							onClick = {
								navigationRepository.navigate(Destinations.allGenres)
							},
							colors = toolbarButtonColors,
						)
					}
				}

				// Settings is always last
				ExpandableIconButton(
					icon = ImageVector.vectorResource(R.drawable.ic_settings),
					label = stringResource(R.string.settings),
					onClick = {
						settingsViewModel.show()
					},
					colors = toolbarButtonColors,
				)
			}
		},
		end = {
			// Clock
			if (clockBehavior == ClockBehavior.ALWAYS || clockBehavior == ClockBehavior.IN_MENUS) {
				ToolbarClock()
			}
		}
	)

	if (showShuffleDialog) {
		ShuffleOptionsDialog(
			userViews = userViews,
			aggregatedLibraries = aggregatedLibraries,
			enableMultiServer = enableMultiServer,
			shuffleContentType = shuffleContentType,
			api = api,
			onDismiss = { showShuffleDialog = false },
			onShuffle = { libraryId, serverId, genreName, contentType, libraryCollectionType ->
				showShuffleDialog = false
				kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
					shuffleManager.shuffle(
						context = context,
						libraryId = libraryId,
						serverId = serverId,
						genreName = genreName,
						contentType = contentType,
						libraryCollectionType = libraryCollectionType
					)
				}
			}
		)
	}
}

fun setupNavbarComposeView(
	composeView: androidx.compose.ui.platform.ComposeView,
	activeButton: NavbarActiveButton = NavbarActiveButton.None,
	activeLibraryId: UUID? = null,
) {
	composeView.setContent {
		JellyfinTheme {
			Navbar(
				activeButton = activeButton,
				activeLibraryId = activeLibraryId,
			)
		}
	}
}
