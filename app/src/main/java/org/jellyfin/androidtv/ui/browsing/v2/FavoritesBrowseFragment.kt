package org.jellyfin.androidtv.ui.browsing.v2

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.HomeVersionTracker
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberCurrentTime
import org.jellyfin.androidtv.ui.composable.requestFocusSettled
import org.jellyfin.androidtv.ui.home.timeLeft
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.sdk.hasTentacleLabel
import org.jellyfin.androidtv.util.sdk.tentacleLabel
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.tentacle.server.core.model.ServerType
import timber.log.Timber
import java.time.Duration
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.sdk.model.api.ImageType as JellyfinImageType

/**
 * Favourites, in the guide's style like the Recordings screen (Lucas, 24 Oct): the bold title with
 * Home and the clock, the focused card's header, then rows that scroll sideways in the order of
 * Tentacle web's Favourites ([FavoriteRowKind]: Movies, Shows, the favourite channels third, …).
 * OK on a channel tunes it.
 *
 * Follows changes while on screen, in place (focus and scroll stay): a favourite added or removed
 * anywhere (Jellyfin's UserDataChanged: Tentacle's My Channels, the web, the guide), Tentacle's
 * Live TV setup (LibraryChanged), the socket coming back, and once a minute for what's on now.
 * Back on a card goes to the top bar first, as on Home; Back there leaves.
 */
class FavoritesBrowseFragment : Fragment() {

	private val viewModel: FavoritesBrowseViewModel by viewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val itemLauncher: ItemLauncher by inject()
	private val sessionRepository: SessionRepository by inject()
	private val serverRepository: ServerRepository by inject()
	private val homeVersionTracker: HomeVersionTracker by inject()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		)
		setContent { JellyfinTheme { FavoritesContent() } }
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// First open: load; back from a details page or the player: read again in place
		viewModel.initialize()
		followChanges()
	}

	private fun followChanges() {
		val owner = viewLifecycleOwner
		owner.lifecycleScope.launch {
			owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				var pending: Job? = null
				fun refreshSoon(delayMs: Long) {
					pending?.cancel()
					pending = launch {
						delay(delayMs)
						viewModel.refresh()
					}
				}

				if (serverRepository.currentServer.value?.serverType != ServerType.EMBY) launch {
					// The SDK's flow can end silently after a Jellyfin restart: subscribe again.
					while (isActive) {
						try {
							api().webSocket.subscribe<UserDataChangedMessage>().collect { message ->
								val data = message.data ?: return@collect
								val me = sessionRepository.currentSession.value?.userId
								if (me != null && data.userId != me) return@collect
								val changes = data.userDataList.map { it.itemId.toString() to it.isFavorite }
								if (favoritesChanged(viewModel.shownIds, changes)) refreshSoon(FAVORITE_SETTLE_MS)
							}
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							Timber.w(e, "Favorites: UserDataChanged subscription error")
						}
						delay(5.seconds)
					}
				}

				launch { homeVersionTracker.changes.collect { refreshSoon(LIBRARY_SETTLE_MS) } }
				launch { homeVersionTracker.reconnects.collect { refreshSoon(0) } }
				launch {
					while (isActive) {
						delay(60.seconds)
						refreshSoon(0)
					}
				}
			}
		}
	}

	private fun api() = viewModel.api

	@Composable
	private fun FavoritesContent() {
		val uiState by viewModel.uiState.collectAsState()
		val homeFocus = remember { FocusRequester() }
		// One per card, to put focus back on a card by id (opening, back from a page, Down from the top bar)
		val cardFocus = remember { HashMap<String, FocusRequester>() }
		val focusCard: (String?) -> Boolean = { id -> id != null && runCatching { cardFocus[id]?.requestFocus() != null }.getOrDefault(false) }
		var contentHasFocus by remember { mutableStateOf(false) }
		var topHasFocus by remember { mutableStateOf(false) }
		val now = rememberMinute()

		// Back on a card: the top bar first, as on Home (Back there leaves the screen)
		BackHandler(enabled = contentHasFocus) {
			runCatching { homeFocus.requestFocus() }
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.background(GuideBackground),
		) {
			FavoritesTopBar(
				homeFocus = homeFocus,
				// Down from the top bar: the card Back left (or last focused), else the nearest one
				onDown = { focusCard(viewModel.lastFocusedId) },
				modifier = Modifier.onFocusChanged { topHasFocus = it.hasFocus },
			)
			FavoritesHeader(now)

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.onFocusChanged { contentHasFocus = it.hasFocus },
			) {
				when {
					uiState.isLoading -> CircularProgressIndicator(
						modifier = Modifier.size(48.dp).align(Alignment.Center),
						color = GuideAccent,
					)
					uiState.rows.isEmpty() -> Text(
						text = stringResource(R.string.msg_no_favorites),
						fontSize = 16.sp,
						color = GuideTextMuted,
						modifier = Modifier.padding(start = 60.dp, top = 24.dp, end = 60.dp),
					)
					else -> FavoriteRows(uiState.rows, cardFocus, now)
				}
			}
		}

		// Opening (or back from a details page): the card last focused, else the first card.
		// After a change in place: only when the focused card went away (the next one in its row).
		var placed by remember { mutableStateOf(false) }
		LaunchedEffect(uiState.isLoading, uiState.rows) {
			if (uiState.isLoading) return@LaunchedEffect
			val refocus = uiState.refocus
			if (placed && refocus != null && !topHasFocus) {
				cardFocus.getOrPut(refocus) { FocusRequester() }
					.requestFocusSettled(isFocused = { viewModel.lastFocusedId == refocus })
				return@LaunchedEffect
			}
			if (placed && (contentHasFocus || topHasFocus)) return@LaunchedEffect
			placed = true
			val target = favoriteFocusTarget(uiState.rows, viewModel.lastFocusedId, viewModel.lastFocusedKind, viewModel.lastFocusedIndex)
			val requester = target?.let { cardFocus.getOrPut(it) { FocusRequester() } }
			if (requester != null) requester.requestFocusSettled(isFocused = { contentHasFocus })
			else homeFocus.requestFocusSettled(isFocused = { topHasFocus })
		}
	}

	@Composable
	private fun FavoritesTopBar(homeFocus: FocusRequester, onDown: () -> Boolean, modifier: Modifier = Modifier) {
		Row(
			modifier = modifier
				.fillMaxWidth()
				.padding(start = 24.dp, end = 32.dp, top = 19.dp)
				.height(34.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = stringResource(R.string.lbl_favorites),
				fontSize = 23.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White,
			)
			Spacer(modifier = Modifier.weight(1f))
			HomeButton(
				onClick = { navigationRepository.navigate(Destinations.home) },
				modifier = Modifier
					.focusRequester(homeFocus)
					.onPreviewKeyEvent { event ->
						event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onDown()
					},
			)
			Spacer(modifier = Modifier.width(12.dp))
			val time by rememberCurrentTime()
			Text(text = time, fontSize = 20.sp, fontWeight = FontWeight.Light, color = Color.White)
		}
	}

	/** The focused card, as the guide's header: picture, name, a purple line, a grey line. */
	@Composable
	private fun FavoritesHeader(now: LocalDateTime) {
		val item by viewModel.focusedItem.collectAsState()
		val px = rememberCardPx(160, 90)
		val format = rememberRecordingFormat()
		val focused = item
		when {
			focused == null -> GuideStyleHeader(imageUrl = null, title = "", timeLine = "", detail = null)
			focused.type == BaseItemKind.TV_CHANNEL -> {
				val program = focused.currentProgram?.takeIf { isOnNow(it, now) }
				GuideStyleHeader(
					imageUrl = remember(focused.id, px) { channelLogoUrl(focused, px) },
					imageScale = ContentScale.Fit,
					title = program?.name ?: focused.name.orEmpty(),
					timeLine = listOfNotNull(
						program?.startDate?.let { format.span(it, program.endDate) },
						program?.endDate?.let { timeLeft(now, it) },
					).joinToString(" · "),
					detail = listOfNotNull(focused.number, focused.name).joinToString(" "),
				)
			}
			else -> GuideStyleHeader(
				imageUrl = remember(focused.id, px) { wideImageUrl(focused, px) },
				title = focused.name.orEmpty(),
				timeLine = cardSubtitle(focused),
				detail = focused.overview,
			)
		}
	}

	@Composable
	private fun FavoriteRows(
		rows: List<FavoriteRow>,
		cardFocus: HashMap<String, FocusRequester>,
		now: LocalDateTime,
	) {
		LazyColumn(
			state = viewModel.listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
		) {
			for (row in rows) {
				item(key = row.kind.name, contentType = if (row.kind.wide) "wideRow" else "posterRow") {
					RowTitle(stringResource(rowTitle(row.kind)))
					LazyRow(
						state = viewModel.rowState(row.kind),
						horizontalArrangement = Arrangement.spacedBy(13.dp),
						contentPadding = PaddingValues(horizontal = 60.dp),
					) {
						itemsIndexed(row.items, key = { _, item -> item.id }, contentType = { _, _ -> row.kind.name }) { index, item ->
							val modifier = Modifier.focusRequester(cardFocus.getOrPut(item.id.toString()) { FocusRequester() })
							val onFocused = { viewModel.setFocusedItem(item, row.kind, index) }
							if (row.kind == FavoriteRowKind.CHANNELS || item.type == BaseItemKind.TV_CHANNEL) {
								ChannelCard(item, now, onFocused, modifier)
							} else {
								ItemCard(item, row.kind, onFocused, modifier)
							}
						}
					}
				}
			}
		}
	}

	@Composable
	private fun RowTitle(title: String) {
		Text(
			text = title,
			fontSize = 15.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 60.dp, top = 8.dp, bottom = 7.dp),
		)
	}

	@Composable
	private fun ItemCard(item: BaseItemDto, kind: FavoriteRowKind, onFocused: () -> Unit, modifier: Modifier) {
		val width = if (kind.wide) WIDE_WIDTH else POSTER_WIDTH
		val height = if (kind.wide) WIDE_HEIGHT else POSTER_HEIGHT
		val px = rememberCardPx(width, height)
		val imageUrl = remember(item.id, item.imageTags, px, kind) {
			if (kind.wide) wideImageUrl(item, px) else posterImageUrl(item, px)
		}
		GuideCardFrame(
			width = width,
			height = height,
			onClick = { launchItem(item) },
			onMenu = null,
			onFocused = onFocused,
			title = item.name.orEmpty(),
			subtitle = remember(item) { cardSubtitle(item) },
			modifier = modifier,
		) {
			if (imageUrl != null) {
				AsyncImage(
					model = imageUrl,
					contentDescription = item.name,
					modifier = Modifier.fillMaxSize(),
					contentScale = ContentScale.Crop,
				)
			}
			val progress = item.userData?.playedPercentage?.takeIf { it > 0 && item.userData?.played != true }
			if (progress != null) ProgressLine((progress / 100).toFloat())
		}
	}

	/** A favourite channel: its logo whole, the number, what's on now and how far it is. */
	@Composable
	private fun ChannelCard(channel: BaseItemDto, now: LocalDateTime, onFocused: () -> Unit, modifier: Modifier) {
		val px = rememberCardPx(WIDE_WIDTH, WIDE_HEIGHT)
		val logoUrl = remember(channel.id, channel.imageTags, px) { channelLogoUrl(channel, px) }
		val program = channel.currentProgram?.takeIf { isOnNow(it, now) }
		GuideCardFrame(
			width = WIDE_WIDTH,
			height = WIDE_HEIGHT,
			onClick = { launchItem(channel) },
			onMenu = null,
			onFocused = onFocused,
			title = channel.name.orEmpty(),
			subtitle = listOfNotNull(program?.name, program?.endDate?.let { timeLeft(now, it) }).joinToString(" · "),
			modifier = modifier,
		) {
			if (logoUrl != null) {
				AsyncImage(
					model = logoUrl,
					contentDescription = channel.name,
					modifier = Modifier
						.fillMaxSize()
						.padding(horizontal = 30.dp, vertical = 20.dp),
					contentScale = ContentScale.Fit,
				)
			} else {
				Text(
					text = channel.name.orEmpty(),
					fontSize = 16.sp,
					fontWeight = FontWeight.Bold,
					color = Color.White,
					maxLines = 2,
					modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
				)
			}
			channel.number?.takeIf { it.isNotBlank() }?.let { number ->
				Box(
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(6.dp)
						.clip(RoundedCornerShape(3.dp))
						.background(Color.Black.copy(alpha = 0.55f))
						.padding(horizontal = 5.dp, vertical = 1.dp),
				) {
					Text(text = number, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
				}
			}
			if (program != null) ProgressLine(programProgress(program, now))
		}
	}

	@Composable
	private fun androidx.compose.foundation.layout.BoxScope.ProgressLine(fraction: Float) {
		Box(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.fillMaxWidth(fraction.coerceIn(0.02f, 1f))
				.height(3.dp)
				.background(GuideAccent),
		)
	}

	private fun rowTitle(kind: FavoriteRowKind): Int = when (kind) {
		FavoriteRowKind.MOVIES -> R.string.lbl_movies
		FavoriteRowKind.SHOWS -> R.string.lbl_shows
		FavoriteRowKind.CHANNELS -> R.string.lbl_guide_title
		FavoriteRowKind.YOUTUBE -> R.string.lbl_favorites_youtube
		FavoriteRowKind.RECORDINGS -> R.string.lbl_recordings
		FavoriteRowKind.SEASONS -> R.string.lbl_seasons
		FavoriteRowKind.EPISODES -> R.string.lbl_episodes
		FavoriteRowKind.COLLECTIONS -> R.string.lbl_collections
		FavoriteRowKind.PLAYLISTS -> R.string.lbl_playlists
		FavoriteRowKind.VIDEOS -> R.string.lbl_videos
		FavoriteRowKind.PEOPLE -> R.string.lbl_people
	}

	/** The grey line under a card: "Recording · Fri 23 Oct", "YouTube · <channel>", the year, "Show · S2 E13". */
	private fun cardSubtitle(item: BaseItemDto): String {
		if (item.hasTentacleLabel) item.tentacleLabel?.let { return it }
		return when (item.type) {
			BaseItemKind.EPISODE -> listOfNotNull(
				item.seriesName,
				listOfNotNull(item.parentIndexNumber?.let { "S$it" }, item.indexNumber?.let { "E$it" }).joinToString(" ").takeIf { it.isNotEmpty() },
			).joinToString(" · ")
			BaseItemKind.SEASON -> item.seriesName.orEmpty()
			BaseItemKind.PLAYLIST, BaseItemKind.BOX_SET -> item.childCount?.takeIf { it > 0 }?.let {
				resources.getQuantityString(R.plurals.items, it, it)
			}.orEmpty()
			else -> item.productionYear?.toString().orEmpty()
		}
	}

	private fun posterImageUrl(item: BaseItemDto, px: CardPx): String? {
		val image = item.itemImages[JellyfinImageType.PRIMARY]
			?: item.parentImages[JellyfinImageType.PRIMARY]
			?: return null
		return image.getCardUrl(viewModel.api, px.width, px.height)
	}

	/** A 16:9 picture: the Thumb (Tentacle's for recordings), else the Primary (an episode's still), else the show's. */
	private fun wideImageUrl(item: BaseItemDto, px: CardPx): String? {
		val image = item.itemImages[JellyfinImageType.THUMB]
			?: item.itemImages[JellyfinImageType.PRIMARY]
			?: item.parentImages[JellyfinImageType.THUMB]
			?: item.parentImages[JellyfinImageType.PRIMARY]
			?: return null
		return image.getCardUrl(viewModel.api, px.width, px.height)
	}

	private fun channelLogoUrl(channel: BaseItemDto, px: CardPx): String? =
		channel.itemImages[JellyfinImageType.PRIMARY]?.getCardUrl(viewModel.api, px.width, px.height)

	private fun launchItem(item: BaseItemDto) {
		// A channel tunes in (ItemLauncher's LiveTvChannel), anything else opens its page
		itemLauncher.launch(BaseItemDtoBaseRowItem(item), null, requireContext())
	}

	private companion object {
		const val POSTER_WIDTH = 100
		const val POSTER_HEIGHT = 150
		const val WIDE_WIDTH = 200
		const val WIDE_HEIGHT = 112

		/** Jellyfin sends UserDataChanged per item: one read for a burst (several stars at once). */
		const val FAVORITE_SETTLE_MS = 300L
		const val LIBRARY_SETTLE_MS = 1_500L
	}
}

/** The current minute, ticking while on screen (what's on now and time left move on). */
@Composable
private fun rememberMinute(): LocalDateTime {
	var now by remember { mutableStateOf(LocalDateTime.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(60_000L - (System.currentTimeMillis() % 60_000L))
			now = LocalDateTime.now()
		}
	}
	return now
}

private fun isOnNow(program: BaseItemDto, now: LocalDateTime): Boolean {
	val end = program.endDate ?: return false
	val start = program.startDate
	return end.isAfter(now) && (start == null || !start.isAfter(now))
}

private fun programProgress(program: BaseItemDto, now: LocalDateTime): Float {
	val start = program.startDate ?: return 0f
	val end = program.endDate ?: return 0f
	val total = Duration.between(start, end).seconds
	if (total <= 0) return 0f
	return Duration.between(start, now).seconds.toFloat() / total
}
