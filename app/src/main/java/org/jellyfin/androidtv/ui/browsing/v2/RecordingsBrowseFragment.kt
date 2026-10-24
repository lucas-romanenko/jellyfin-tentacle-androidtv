package org.jellyfin.androidtv.ui.browsing.v2

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.eventhandling.RecordingChanges
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.requestFocusSettled
import org.jellyfin.androidtv.ui.itemdetail.programmeRecordClick
import org.jellyfin.androidtv.ui.itemdetail.v2.resumeStartMs
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.RecordingStatus
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.time.LocalDateTime
import org.jellyfin.sdk.model.api.ImageType as JellyfinImageType

/**
 * Recordings (#203): one screen in the guide's style, opened by the guide's Recordings button,
 * with a Recorded | Scheduled | Series switch (Scheduled and Series only for people who may
 * manage recordings).
 * - Recorded: Recording now, Recently recorded, Shows (one card per title, opening its
 *   recordings), Movies, Sports, Other and Needs attention; the header says when the focused
 *   one aired.
 * - Scheduled: what is booked, day by day.
 * - Series: the series set to record.
 * Menu or a long press of OK opens a recording's or a booking's menu. Every part reads again
 * in place when a recording changes anywhere (#202).
 */
class RecordingsBrowseFragment : Fragment() {

	companion object {
		/** Which part opens: a [RecordingsTab] name. */
		const val EXTRA_TAB = "RecordingsTab"
	}

	private val viewModel: RecordingsBrowseViewModel by viewModel()
	private val scheduleViewModel: ScheduleBrowseViewModel by viewModel()
	private val seriesViewModel: SeriesRecordingsBrowseViewModel by viewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val itemLauncher: ItemLauncher by inject()
	private val userRepository: UserRepository by inject()
	private val playbackHelper: PlaybackHelper by inject()
	private val playbackLauncher: PlaybackLauncher by inject()
	private val userPreferences: UserPreferences by inject()

	private val canManage: Boolean get() = Utils.canManageRecordings(userRepository.currentUser.value)

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT,
		)
		setContent { JellyfinTheme { RecordingsContent() } }
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val manage = canManage
		viewModel.initialize()
		if (manage) {
			scheduleViewModel.initialize()
			seriesViewModel.initialize()
		}
		// A recording set or cancelled anywhere (Tentacle's teams and rules too): read again in place.
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				RecordingChanges.changes.collectLatest {
					delay(800)
					viewModel.refresh()
					if (manage) {
						scheduleViewModel.refresh()
						seriesViewModel.refresh()
					}
				}
			}
		}
	}

	@Composable
	private fun RecordingsContent() {
		val manage = remember { canManage }
		val tabs = remember(manage) { recordingsTabs(manage) }
		var tab by rememberSaveable { mutableStateOf(openingRecordingsTab(arguments?.getString(EXTRA_TAB), manage)) }
		val uiState by viewModel.uiState.collectAsState()
		val scheduleState by scheduleViewModel.uiState.collectAsState()
		val seriesState by seriesViewModel.uiState.collectAsState()
		val format = rememberRecordingFormat()
		var recordingSheet by remember { mutableStateOf<BaseItemDto?>(null) }
		var scheduleSheet by remember { mutableStateOf<ScheduleEntry?>(null) }
		val menuScope = rememberCoroutineScope()
		val openRecordingMenu: (BaseItemDto) -> Unit = { item ->
			menuScope.launch { recordingSheet = viewModel.forMenu(item) }
		}

		// Each part keeps its place while another is shown.
		val recordedList = rememberLazyListState()
		val scheduledList = rememberLazyListState()
		val chipFocus = remember { FocusRequester() }
		val contentFocus = remember { FocusRequester() }
		var returnToShow by remember { mutableStateOf<String?>(null) }
		var contentHasFocus by remember { mutableStateOf(false) }
		var chipHasFocus by remember { mutableStateOf(false) }

		val loading = when (tab) {
			RecordingsTab.RECORDED -> uiState.isLoading
			RecordingsTab.SCHEDULED -> scheduleState.isLoading
			RecordingsTab.SERIES -> seriesState.isLoading
		}

		Column(
			modifier = Modifier
				.fillMaxSize()
				.background(GuideBackground),
		) {
			RecordingsTopBar(
				tabs = tabs,
				selected = tab,
				counts = mapOf(
					RecordingsTab.RECORDED to uiState.groups.groupedCount.takeUnless { uiState.isLoading },
					RecordingsTab.SCHEDULED to scheduleState.days.sumOf { it.entries.size }.takeUnless { scheduleState.isLoading },
					RecordingsTab.SERIES to seriesState.seriesTimers.size.takeUnless { seriesState.isLoading },
				),
				onSelect = { tab = it },
				onHome = { navigationRepository.navigate(Destinations.home) },
				selectedChipFocus = chipFocus,
				modifier = Modifier.onFocusChanged { chipHasFocus = it.hasFocus },
			)

			when (tab) {
				RecordingsTab.RECORDED -> RecordingHeader(format)
				RecordingsTab.SERIES -> SeriesHeader()
				RecordingsTab.SCHEDULED -> Spacer(modifier = Modifier.height(12.dp))
			}

			Box(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.onFocusChanged { contentHasFocus = it.hasFocus },
			) {
				if (loading) {
					CircularProgressIndicator(
						modifier = Modifier.size(48.dp).align(Alignment.Center),
						color = GuideAccent,
					)
				} else when (tab) {
					RecordingsTab.RECORDED -> {
						val openShow = uiState.openShow?.let { title -> uiState.groups.shows.firstOrNull { it.title == title } }
						if (openShow != null) {
							BackHandler { viewModel.openShow(null) }
							ShowPage(show = openShow, uiState = uiState, format = format, onMenu = openRecordingMenu)
						} else {
							RecordedRows(
								uiState = uiState,
								format = format,
								listState = recordedList,
								firstFocus = contentFocus,
								returnToShow = returnToShow,
								onShowFocusReturned = { returnToShow = null },
								onOpenShow = { title ->
									returnToShow = title
									viewModel.openShow(title)
								},
								onMenu = openRecordingMenu,
							)
						}
					}
					RecordingsTab.SCHEDULED -> ScheduledAgenda(
						days = scheduleState.days,
						listState = scheduledList,
						firstFocus = contentFocus,
						onOpen = { launchItem(it.item) },
						onMenu = { scheduleSheet = it },
					)
					RecordingsTab.SERIES -> SeriesRows(timers = seriesState.seriesTimers, firstFocus = contentFocus)
				}
			}
		}

		// Opening: the first card or row of the part shown, else its chip (nothing in it yet).
		var placedFocus by remember { mutableStateOf(false) }
		LaunchedEffect(loading) {
			if (loading || placedFocus) return@LaunchedEffect
			placedFocus = true
			// Settled: while the ComposeView doesn't hold Android focus yet, one request gets lost.
			contentFocus.requestFocusSettled(isFocused = { contentHasFocus })
			if (!contentHasFocus) chipFocus.requestFocusSettled(isFocused = { chipHasFocus })
		}

		recordingSheet?.let { item ->
			RecordedActionSheet(item = item, format = format, onDismiss = { recordingSheet = null })
		}
		scheduleSheet?.let { entry ->
			ScheduleActionSheet(entry = entry, onDismiss = { scheduleSheet = null })
		}
	}

	// Recorded

	/** The focused card's header: name, when (date, start–end, channel), genre and state. */
	@Composable
	private fun RecordingHeader(format: RecordingFormat) {
		val focus by viewModel.focusedItem.collectAsState()
		val headerPx = rememberCardPx(160, 90)
		when (val f = focus) {
			null -> GuideStyleHeader(imageUrl = null, title = "", timeLine = "", detail = null)
			is RecordingFocus.Show -> GuideStyleHeader(
				imageUrl = remember(f.show.newest.id, headerPx) { getRecordingImageUrl(f.show.newest, headerPx) },
				title = f.show.title,
				timeLine = listOfNotNull(
					pluralStringResource(R.plurals.lbl_show_recordings, f.show.items.size, f.show.items.size),
					recordingAirStart(f.show.newest)?.let { stringResource(R.string.lbl_show_newest, format.day(it)) },
				).joinToString(" · "),
				detail = f.show.newest.genres?.firstOrNull(),
			)
			is RecordingFocus.Recording -> GuideStyleHeader(
				imageUrl = remember(f.item.id, headerPx) { getRecordingImageUrl(f.item, headerPx) },
				title = f.item.name.orEmpty(),
				timeLine = recordingWhenLine(f.item, f.recordingNow, format),
				detail = listOfNotNull(
					f.item.genres?.firstOrNull(),
					recordingStateLabel(f.item, f.recordingNow != null),
				).joinToString(" · "),
				detailColor = if (f.recordingNow != null) RecordingRed else GuideTextMuted,
			)
		}
	}

	/** "Fri 23 Oct · 7:55 PM – 11:34 PM · TSN 2" (recordings carry a channel only while recording). */
	@Composable
	private fun recordingWhenLine(item: BaseItemDto, now: RecordingNowInfo?, format: RecordingFormat): String {
		val parts = mutableListOf<String>()
		if (now != null) {
			now.since?.let {
				parts += format.day(it)
				parts += stringResource(R.string.lbl_recording_since, format.time(it))
			}
		} else {
			val span = recordingSpan(item)
			val aired = recordingAirStart(item)
			when {
				span != null -> {
					parts += format.day(aired ?: span.first)
					parts += format.span(span.first, span.second)
				}
				aired != null -> {
					parts += format.day(aired)
					parts += format.time(aired)
				}
			}
		}
		(now?.channelName ?: item.channelName)?.takeIf { it.isNotBlank() }?.let { parts += it }
		return parts.joinToString(" · ")
	}

	@Composable
	private fun recordingStateLabel(item: BaseItemDto, recordingNow: Boolean): String {
		val resume = recordingResumeTicks(item)
		return when {
			recordingNow -> stringResource(R.string.lbl_recording_now)
			isRecordingTooShort(item) -> stringResource(R.string.lbl_recording_under_a_minute)
			item.userData?.played == true -> stringResource(R.string.lbl_watched)
			resume != null -> stringResource(R.string.lbl_recording_watched_to, TimeUtils.formatMillis(resume / 10_000))
			else -> stringResource(R.string.lbl_recording_new)
		}
	}

	/** Line 2 of a card: "Fri 23 Oct · TSN 2", or the air time when the channel isn't known. */
	private fun cardLine2(item: BaseItemDto, now: RecordingNowInfo?, format: RecordingFormat, since: (String) -> String): String {
		val channel = (now?.channelName ?: item.channelName)?.takeIf { it.isNotBlank() }
		if (now != null) return listOfNotNull(now.since?.let { since(format.time(it)) }, channel).joinToString(" · ")
		val aired = recordingAirStart(item) ?: return channel.orEmpty()
		return listOf(format.day(aired), channel ?: format.time(aired)).joinToString(" · ")
	}

	@Composable
	private fun RecordedRows(
		uiState: RecordingsBrowseUiState,
		format: RecordingFormat,
		listState: LazyListState,
		firstFocus: FocusRequester,
		returnToShow: String?,
		onShowFocusReturned: () -> Unit,
		onOpenShow: (String) -> Unit,
		onMenu: (BaseItemDto) -> Unit,
	) {
		val groups = uiState.groups
		val rows = listOf(
			Triple("recordingNow", R.string.lbl_recording_now, groups.recordingNow),
			Triple("recentlyRecorded", R.string.lbl_recently_recorded, groups.recentlyRecorded),
			Triple("shows", R.string.lbl_shows, null),
			Triple("movies", R.string.lbl_movies, groups.movies),
			Triple("sports", R.string.lbl_sports, groups.sports),
			Triple("other", R.string.lbl_other, groups.other),
			Triple("needsAttention", R.string.lbl_needs_attention, groups.needsAttention),
		)
		// The first card of the first row shown takes the opening focus.
		val firstKey = rows.firstOrNull { (key, _, items) -> if (key == "shows") groups.shows.isNotEmpty() else !items.isNullOrEmpty() }?.first

		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
		) {
			if (groups.groupedCount == 0) {
				item(key = "empty") { EmptyLine(stringResource(R.string.lbl_no_recordings)) }
			}

			for ((key, titleRes, items) in rows) {
				if (key == "shows") {
					if (groups.shows.isEmpty()) continue
					item(key = key) {
						RowTitle(stringResource(titleRes))
						LazyRow(
							horizontalArrangement = Arrangement.spacedBy(13.dp),
							contentPadding = PaddingValues(horizontal = 60.dp),
						) {
							items(groups.shows, key = { "show:" + it.title }, contentType = { "showCard" }) { show ->
								val focusBack = if (show.title == returnToShow) remember { FocusRequester() } else null
								if (focusBack != null) LaunchedEffect(Unit) {
									runCatching { focusBack.requestFocus() }
									onShowFocusReturned()
								}
								ShowCard(
									show = show,
									format = format,
									modifier = when {
										focusBack != null -> Modifier.focusRequester(focusBack)
										key == firstKey && show === groups.shows.first() -> Modifier.focusRequester(firstFocus)
										else -> Modifier
									},
									onClick = { onOpenShow(show.title) },
									onFocused = { viewModel.setFocused(RecordingFocus.Show(show)) },
								)
							}
						}
					}
					continue
				}
				if (items.isNullOrEmpty()) continue
				item(key = key) {
					RowTitle(stringResource(titleRes))
					LazyRow(
						horizontalArrangement = Arrangement.spacedBy(13.dp),
						contentPadding = PaddingValues(horizontal = 60.dp),
					) {
						items(items, key = { it.id }, contentType = { "recordingCard" }) { item ->
							val now = uiState.recordingNow[item.id]
							RecordingCard(
								item = item,
								recordingNow = now,
								format = format,
								onClick = { launchItem(item) },
								onMenu = { onMenu(item) },
								onFocused = { viewModel.setFocused(RecordingFocus.Recording(item, now)) },
								modifier = if (key == firstKey && item === items.first()) Modifier.focusRequester(firstFocus) else Modifier,
							)
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

	/** A show's recordings, newest first, in place of the rows; Back returns to the rows. */
	@Composable
	private fun ShowPage(
		show: RecordedShow,
		uiState: RecordingsBrowseUiState,
		format: RecordingFormat,
		onMenu: (BaseItemDto) -> Unit,
	) {
		val firstFocus = remember { FocusRequester() }
		Column(modifier = Modifier.fillMaxWidth()) {
			RowTitle(show.title + " · " + pluralStringResource(R.plurals.lbl_show_recordings, show.items.size, show.items.size))
			LazyVerticalGrid(
				columns = GridCells.Adaptive(200.dp),
				horizontalArrangement = Arrangement.spacedBy(13.dp),
				verticalArrangement = Arrangement.spacedBy(15.dp),
				contentPadding = PaddingValues(start = 60.dp, end = 60.dp, top = 4.dp, bottom = 24.dp),
				modifier = Modifier.fillMaxHeight(),
			) {
				items(show.items, key = { it.id }, contentType = { "recordingCard" }) { item ->
					val now = uiState.recordingNow[item.id]
					RecordingCard(
						item = item,
						recordingNow = now,
						format = format,
						onClick = { launchItem(item) },
						onMenu = { onMenu(item) },
						onFocused = { viewModel.setFocused(RecordingFocus.Recording(item, now)) },
						modifier = if (item.id == show.items.first().id) Modifier.focusRequester(firstFocus) else Modifier,
					)
				}
			}
		}
		LaunchedEffect(show.title) {
			runCatching { firstFocus.requestFocus() }
		}
	}

	/**
	 * A 16:9 card as the mockup's: a purple outline when focused (no zoom, cheap on the Sony),
	 * then the name and a grey line under it.
	 */
	@Composable
	private fun CardFrame(
		onClick: () -> Unit,
		onMenu: (() -> Unit)?,
		onFocused: () -> Unit,
		title: String,
		subtitle: String,
		subtitleColor: Color = CardSubText,
		modifier: Modifier = Modifier,
		art: @Composable BoxScope.() -> Unit,
	) {
		val interactionSource = remember { MutableInteractionSource() }
		val isFocused by interactionSource.collectIsFocusedAsState()

		LaunchedEffect(isFocused) {
			if (isFocused) onFocused()
		}

		Column(
			modifier = modifier
				.width(CARD_WIDTH.dp)
				.onKeyEvent { event ->
					// The Menu key opens the menu; a long press of OK does too (onLongClick).
					val native = event.nativeKeyEvent
					if (onMenu != null && native.action == KeyEvent.ACTION_UP && native.keyCode == KeyEvent.KEYCODE_MENU) {
						onMenu()
						true
					} else false
				}
				.then(
					if (onMenu != null) Modifier.combinedClickable(
						interactionSource = interactionSource,
						indication = null,
						onClick = onClick,
						onLongClick = onMenu,
					) else Modifier.clickable(
						interactionSource = interactionSource,
						indication = null,
						onClick = onClick,
					)
				),
		) {
			Box(
				modifier = Modifier
					.width(CARD_WIDTH.dp)
					.height(CARD_HEIGHT.dp)
					.clip(RoundedCornerShape(4.dp))
					.background(CardArtBackground)
					// Drawn over the picture's edge: focus doesn't move or resize anything
					.then(if (isFocused) Modifier.border(2.5.dp, GuideAccent, RoundedCornerShape(4.dp)) else Modifier),
			) {
				art()
			}
			Text(
				text = title,
				fontSize = 13.sp,
				fontWeight = FontWeight.Medium,
				color = if (isFocused) Color.White else CardTitleText,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 6.dp),
			)
			if (subtitle.isNotEmpty()) {
				Text(
					text = subtitle,
					fontSize = 11.sp,
					color = subtitleColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}

	@Composable
	private fun RecordingCard(
		item: BaseItemDto,
		recordingNow: RecordingNowInfo?,
		format: RecordingFormat,
		onClick: () -> Unit,
		onMenu: () -> Unit,
		onFocused: () -> Unit,
		modifier: Modifier = Modifier,
	) {
		val context = LocalContext.current
		val subtitle = remember(item, recordingNow, format) {
			cardLine2(item, recordingNow, format) { context.getString(R.string.lbl_recording_since, it) }
		}
		CardFrame(
			onClick = onClick,
			onMenu = onMenu,
			onFocused = onFocused,
			title = item.name.orEmpty(),
			subtitle = subtitle,
			modifier = modifier,
		) {
			CardImage(item)
			if (recordingNow != null) {
				RecChip(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
			}
			val resume = recordingResumeTicks(item)
			val runtime = item.runTimeTicks
			val progress = if (resume != null && runtime != null && runtime > 0) (resume.toFloat() / runtime).coerceIn(0.02f, 1f) else null
			if (progress != null) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth(progress)
						.height(3.dp)
						.background(GuideAccent),
				)
			}
		}
	}

	/** One card per show: its newest recording's picture, the count, and when the newest aired. */
	@Composable
	private fun ShowCard(
		show: RecordedShow,
		format: RecordingFormat,
		onClick: () -> Unit,
		onFocused: () -> Unit,
		modifier: Modifier = Modifier,
	) {
		val newest = recordingAirStart(show.newest)
		CardFrame(
			onClick = onClick,
			onMenu = null,
			onFocused = onFocused,
			title = show.title,
			subtitle = listOfNotNull(
				pluralStringResource(R.plurals.lbl_show_recordings, show.items.size, show.items.size),
				newest?.let { stringResource(R.string.lbl_show_newest, format.day(it)) },
			).joinToString(" · "),
			modifier = modifier,
		) {
			CardImage(show.newest)
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(6.dp)
					.clip(RoundedCornerShape(3.dp))
					.background(Color.Black.copy(alpha = 0.55f))
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(text = show.items.size.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
			}
		}
	}

	/** The item's Thumb (16:9, made by Tentacle), else its Primary, else today's placeholder. */
	@Composable
	private fun CardImage(item: BaseItemDto) {
		val px = rememberCardPx(CARD_WIDTH, CARD_HEIGHT)
		val imageUrl = remember(item.id, item.imageTags, px) { getRecordingImageUrl(item, px) }
		if (imageUrl != null) {
			AsyncImage(
				model = imageUrl,
				contentDescription = item.name,
				modifier = Modifier.fillMaxSize(),
				contentScale = ContentScale.Crop,
			)
		} else {
			PlaceholderArt(R.drawable.ic_record)
		}
	}

	@Composable
	private fun PlaceholderArt(iconRes: Int) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(iconRes),
				contentDescription = null,
				modifier = Modifier.size(44.dp),
				tint = Color.White.copy(alpha = 0.2f),
			)
		}
	}

	/** Resume, Play from beginning, Mark watched, Delete recording… (when allowed), Details. */
	@Composable
	private fun RecordedActionSheet(item: BaseItemDto, format: RecordingFormat, onDismiss: () -> Unit) {
		val context = LocalContext.current
		val resume = recordingResumeTicks(item)
		val played = item.userData?.played == true
		val actions = buildList {
			if (resume != null) {
				val prerollSeconds = userPreferences[UserPreferences.resumeSubtractDuration].toIntOrNull() ?: 0
				val startMs = resumeStartMs(item, prerollSeconds)
				add(SheetAction(context.getString(R.string.lbl_resume_from, TimeUtils.formatMillis(startMs.toLong()))) { play(item, startMs) })
				add(SheetAction(context.getString(R.string.lbl_from_beginning)) { play(item, 0) })
			} else {
				add(SheetAction(context.getString(R.string.lbl_play)) { play(item, 0) })
			}
			add(SheetAction(context.getString(if (played) R.string.lbl_mark_unwatched else R.string.lbl_mark_watched)) {
				viewModel.setPlayed(item, !played) { ok ->
					if (!ok) Toast.makeText(context, R.string.msg_unable_to_update, Toast.LENGTH_LONG).show()
				}
			})
			if (canOfferRecordingDelete(item)) {
				add(SheetAction(context.getString(R.string.lbl_delete_recording)) { confirmDelete(item, format) })
			}
			add(SheetAction(context.getString(R.string.lbl_details)) { launchItem(item) })
		}
		RecordingActionSheet(title = item.name.orEmpty(), actions = actions, onDismiss = onDismiss)
	}

	/** Always asks first; one recording at a time. */
	private fun confirmDelete(item: BaseItemDto, format: RecordingFormat) {
		val context = requireContext()
		val label = listOfNotNull(item.name, recordingAirStart(item)?.let { format.day(it) }).joinToString(" · ")
		AlertDialog.Builder(context)
			.setTitle(R.string.msg_delete_recording_title)
			.setMessage(getString(R.string.msg_delete_recording, label))
			.setNegativeButton(R.string.lbl_no, null)
			.setPositiveButton(R.string.lbl_delete) { _, _ ->
				val appContext = context.applicationContext
				viewModel.delete(item) { ok ->
					Toast.makeText(
						appContext,
						if (ok) appContext.getString(R.string.item_deleted, item.name.orEmpty()) else appContext.getString(R.string.msg_unable_to_delete_recording),
						Toast.LENGTH_LONG,
					).show()
				}
			}
			.show()
	}

	/** Plays [item] from [positionMs] (0 is the user's own "from the beginning"). */
	private fun play(item: BaseItemDto, positionMs: Int) {
		playbackHelper.getItemsToPlay(
			requireContext(),
			item,
			false,
			false,
			object : Response<List<BaseItemDto>>(lifecycle) {
				override fun onResponse(response: List<BaseItemDto>) {
					if (!isActive || response.isEmpty()) return
					playbackLauncher.launch(requireContext(), response, positionMs, false, 0, false)
				}
			},
		)
	}

	// Scheduled

	/** Programme details, Cancel this recording… and Cancel series… (for people who may manage recordings). */
	@Composable
	private fun ScheduleActionSheet(entry: ScheduleEntry, onDismiss: () -> Unit) {
		val context = LocalContext.current
		val actions = buildList {
			add(SheetAction(context.getString(R.string.lbl_programme_details)) { launchItem(entry.item) })
			if (canManage && entry.timerId != null) {
				add(SheetAction(
					label = context.getString(R.string.lbl_cancel_this_recording),
					hint = if (entry.isSeries) context.getString(R.string.hint_cancel_this_recording_series) else null,
				) { confirmCancel(entry) })
			}
			if (canManage && entry.seriesTimerId != null) {
				add(SheetAction(
					label = context.getString(R.string.lbl_cancel_series_ellipsis),
					hint = context.getString(R.string.hint_cancel_series),
				) { confirmCancelSeries(entry) })
			}
		}
		RecordingActionSheet(title = entry.name, actions = actions, onDismiss = onDismiss)
	}

	/** #202's wording: "Cancel this recording?", or that cancelling stops a recording in progress. */
	private fun confirmCancel(entry: ScheduleEntry) {
		val start = if (entry.status == RecordingStatus.IN_PROGRESS) LocalDateTime.MIN else entry.start
		programmeRecordClick(
			timerId = entry.timerId,
			startDate = start,
			now = LocalDateTime.now(),
			book = {},
			confirm = { message, onYes ->
				AlertDialog.Builder(requireContext())
					.setTitle(getString(R.string.lbl_cancel_recording))
					.setMessage(getString(message))
					.setNegativeButton(R.string.lbl_no, null)
					.setPositiveButton(R.string.lbl_yes) { _, _ -> onYes() }
					.show()
			},
			cancel = { timerId -> scheduleViewModel.cancel(timerId, null, ::cancelled) },
		)
	}

	private fun confirmCancelSeries(entry: ScheduleEntry) {
		AlertDialog.Builder(requireContext())
			.setTitle(getString(R.string.lbl_cancel_series))
			.setMessage(getString(R.string.msg_cancel_entire_series))
			.setNegativeButton(R.string.lbl_no, null)
			.setPositiveButton(R.string.lbl_yes) { _, _ -> scheduleViewModel.cancel(null, entry.seriesTimerId, ::cancelled) }
			.show()
	}

	private fun cancelled(ok: Boolean) {
		val context = context ?: return
		Toast.makeText(context, if (ok) R.string.msg_recording_cancelled else R.string.msg_unable_to_cancel, Toast.LENGTH_SHORT).show()
	}

	// Series

	@Composable
	private fun SeriesHeader() {
		val timer by seriesViewModel.focusedTimer.collectAsState()
		GuideStyleHeader(
			imageUrl = null,
			title = timer?.name.orEmpty(),
			timeLine = timer?.let { seriesTimerLine(it) }.orEmpty(),
			detail = null,
		)
	}

	@Composable
	private fun SeriesRows(timers: List<SeriesTimerInfoDto>, firstFocus: FocusRequester) {
		// Timers without an id (none expected) are kept, keyed by position.
		val distinct = remember(timers) { timers.distinctBy { it.id ?: it } }
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
		) {
			if (distinct.isEmpty()) {
				item(key = "empty") { EmptyLine(stringResource(R.string.lbl_no_series_recordings)) }
				return@LazyColumn
			}
			item(key = "series") {
				RowTitle(stringResource(R.string.lbl_series_recordings))
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(13.dp),
					contentPadding = PaddingValues(horizontal = 60.dp),
				) {
					items(
						count = distinct.size,
						key = { index -> distinct[index].id ?: "timer-index-$index" },
						contentType = { "seriesTimerCard" },
					) { index ->
						val timer = distinct[index]
						CardFrame(
							onClick = { launchSeriesTimer(timer) },
							onMenu = null,
							onFocused = { seriesViewModel.setFocusedTimer(timer) },
							title = timer.name.orEmpty(),
							subtitle = seriesTimerLine(timer),
							modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
						) {
							PlaceholderArt(R.drawable.ic_record_series)
						}
					}
				}
			}
		}
	}

	/** "TSN 2 · Daily", or "All channels". */
	private fun seriesTimerLine(timer: SeriesTimerInfoDto): String {
		val channel = if (timer.recordAnyChannel == true) getString(R.string.all_channels) else timer.channelName
		return listOfNotNull(channel?.takeIf { it.isNotBlank() }, timer.dayPattern?.toString()).joinToString(" · ")
	}

	private fun launchSeriesTimer(timer: SeriesTimerInfoDto) {
		val id = timer.id?.toUUIDOrNull() ?: return
		navigationRepository.navigate(Destinations.seriesTimerDetails(id, timer))
	}

	// Shared

	private fun getRecordingImageUrl(item: BaseItemDto, px: CardPx): String? {
		val image = item.itemImages[JellyfinImageType.THUMB]
			?: item.itemImages[JellyfinImageType.PRIMARY]
			?: item.parentImages[JellyfinImageType.THUMB]
			?: item.parentImages[JellyfinImageType.PRIMARY]
			?: return null
		return image.getCardUrl(viewModel.api, px.width, px.height)
	}

	private fun launchItem(item: BaseItemDto) {
		itemLauncher.launch(BaseItemDtoBaseRowItem(item), null, requireContext())
	}
}

private const val CARD_WIDTH = 200
private const val CARD_HEIGHT = 112

// The cards' colours, from the mockup (recorded.png).
private val CardArtBackground = Color(0xFF1A1D2E)
private val CardTitleText = Color(0xFFE6E4F0)
private val CardSubText = Color(0xFF8D8AA3)
