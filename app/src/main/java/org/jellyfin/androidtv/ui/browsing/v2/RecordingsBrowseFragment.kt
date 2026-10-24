package org.jellyfin.androidtv.ui.browsing.v2

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.data.service.BlurContext
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemdetail.v2.resumeStartMs
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.jellyfin.sdk.model.api.ImageType as JellyfinImageType

/**
 * Recorded TV (#203): the focused card's header says when it aired, and the recordings are grouped
 * into Recording now, Recently recorded, Shows (one card per title, opening its recordings),
 * Movies, Sports, Other and Needs attention. Menu or a long press of OK opens the action sheet.
 */
class RecordingsBrowseFragment : Fragment() {

	private val viewModel: RecordingsBrowseViewModel by viewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val backgroundService: BackgroundService by inject()
	private val itemLauncher: ItemLauncher by inject()
	private val userRepository: UserRepository by inject()
	private val playbackHelper: PlaybackHelper by inject()
	private val playbackLauncher: PlaybackLauncher by inject()
	private val userPreferences: UserPreferences by inject()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val mainContainer = FrameLayout(requireContext()).apply {
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT,
			)
		}

		val contentView = ComposeView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			)
			setContent { JellyfinTheme { RecordingsBrowseContent() } }
		}
		mainContainer.addView(contentView)

		return mainContainer
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.initialize()
		// A recording set or cancelled anywhere (Tentacle's teams and rules too): read again in place.
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				RecordingChanges.changes.collectLatest {
					delay(800)
					viewModel.refresh()
				}
			}
		}
	}

	@Composable
	private fun RecordingsBrowseContent() {
		val uiState by viewModel.uiState.collectAsState()
		val format = rememberRecordingFormat()
		var sheetFor by remember { mutableStateOf<BaseItemDto?>(null) }
		// Kept while a show page is open, so Back lands on the same row and show card.
		val rowsListState = rememberLazyListState()
		var returnToShow by remember { mutableStateOf<String?>(null) }

		Box(modifier = Modifier.fillMaxSize()) {
			// Backdrop is drawn once at the activity level (MainActivity binding.background)
			// behind all fragments — drawing it again here doubled the fullscreen GPU cost.

			val currentBg by backgroundService.currentBackground.collectAsState()
			val overlayAlpha = if (currentBg != null) 0.45f else 0.75f
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(NavyBackground.copy(alpha = overlayAlpha)),
			)

			Column(modifier = Modifier.fillMaxSize()) {
				RecordingsHeader(format)

				val openShow = uiState.openShow?.let { title -> uiState.groups.shows.firstOrNull { it.title == title } }
				when {
					uiState.isLoading -> {
						Box(
							Modifier
								.fillMaxSize()
								.weight(1f),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator(
								modifier = Modifier.size(48.dp),
								color = JellyfinBlue,
							)
						}
					}
					openShow != null -> {
						BackHandler { viewModel.openShow(null) }
						ShowPage(
							show = openShow,
							uiState = uiState,
							format = format,
							onMenu = { sheetFor = it },
							modifier = Modifier.weight(1f),
						)
					}
					else -> {
						RecordingsRows(
							uiState = uiState,
							format = format,
							listState = rowsListState,
							returnToShow = returnToShow,
							onShowFocusReturned = { returnToShow = null },
							onOpenShow = { title ->
								returnToShow = title
								viewModel.openShow(title)
							},
							onMenu = { sheetFor = it },
							modifier = Modifier.weight(1f),
						)
					}
				}

				LibraryStatusBar(
					statusText = stringResource(R.string.lbl_recorded_tv),
					counterText = if (uiState.isLoading) "" else uiState.groups.groupedCount.toString(),
				)
			}

			sheetFor?.let { item ->
				RecordedActionSheet(item = item, format = format, onDismiss = { sheetFor = null })
			}
		}
	}

	@Composable
	private fun RecordingsHeader(format: RecordingFormat) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(start = 60.dp, end = 60.dp, top = 12.dp, bottom = 4.dp),
		) {
			Box(
				modifier = Modifier.fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = stringResource(R.string.lbl_recorded_tv),
					fontSize = 26.sp,
					fontWeight = FontWeight.Light,
					color = Color.White,
				)
			}

			Spacer(modifier = Modifier.height(6.dp))

			RecordingHud(format = format, modifier = Modifier.fillMaxWidth())

			Spacer(modifier = Modifier.height(6.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				LibraryToolbarButton(
					iconRes = R.drawable.ic_house,
					contentDescription = stringResource(R.string.home),
					onClick = { navigationRepository.navigate(Destinations.home) },
				)
			}
		}
	}

	/** The focused card's header: name, when (date, start–end, channel) and its state. */
	@Composable
	private fun RecordingHud(format: RecordingFormat, modifier: Modifier = Modifier) {
		val focus by viewModel.focusedItem.collectAsState()
		Column(
			modifier = modifier.defaultMinSize(minHeight = 66.dp),
			verticalArrangement = Arrangement.Center,
		) {
			val (title, line2, state) = when (val f = focus) {
				null -> return@Column
				is RecordingFocus.Show -> Triple(
					f.show.title,
					listOfNotNull(
						pluralStringResource(R.plurals.lbl_show_recordings, f.show.items.size, f.show.items.size),
						recordingAirStart(f.show.newest)?.let { stringResource(R.string.lbl_show_newest, format.day(it)) },
					).joinToString(" · "),
					null,
				)
				is RecordingFocus.Recording -> Triple(
					f.item.name.orEmpty(),
					recordingWhenLine(f.item, f.recordingNow, format),
					recordingStateLabel(f.item, f.recordingNow != null),
				)
			}
			Box(modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200)) {
				Text(
					text = title,
					fontSize = 20.sp,
					fontWeight = FontWeight.SemiBold,
					color = Color.White,
					maxLines = 1,
					overflow = TextOverflow.Clip,
				)
			}
			if (line2.isNotEmpty()) {
				Text(text = line2, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
			}
			if (state != null) {
				Text(
					text = state,
					fontSize = 13.sp,
					color = if (focus.let { it is RecordingFocus.Recording && it.recordingNow != null }) RecordingRed else Color.White.copy(alpha = 0.55f),
					maxLines = 1,
				)
			}
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
	private fun recordingStateLabel(item: BaseItemDto, recordingNow: Boolean): String? {
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
	private fun RecordingsRows(
		uiState: RecordingsBrowseUiState,
		format: RecordingFormat,
		listState: androidx.compose.foundation.lazy.LazyListState,
		returnToShow: String?,
		onShowFocusReturned: () -> Unit,
		onOpenShow: (String) -> Unit,
		onMenu: (BaseItemDto) -> Unit,
		modifier: Modifier = Modifier,
	) {
		val bgDebouncer = rememberFocusDebouncer()
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
		val canManage = Utils.canManageRecordings(userRepository.currentUser.value)

		LazyColumn(
			state = listState,
			modifier = modifier.fillMaxWidth(),
			contentPadding = PaddingValues(bottom = 16.dp),
		) {
			if (canManage) {
				item(key = "views") {
					RecordingsViewsRow()
				}
			}

			if (groups.groupedCount == 0) {
				item(key = "empty") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 40.dp),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = stringResource(R.string.lbl_no_recordings),
							fontSize = 16.sp,
							color = Color.White.copy(alpha = 0.5f),
						)
					}
				}
			}

			for ((key, titleRes, items) in rows) {
				if (key == "shows") {
					if (groups.shows.isEmpty()) continue
					item(key = key) {
						RowTitle(stringResource(titleRes))
						LazyRow(
							horizontalArrangement = Arrangement.spacedBy(12.dp),
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
									modifier = if (focusBack != null) Modifier.focusRequester(focusBack) else Modifier,
									onClick = { onOpenShow(show.title) },
									onFocused = {
										viewModel.setFocused(RecordingFocus.Show(show))
										bgDebouncer.submit { backgroundService.setBackground(show.newest, BlurContext.BROWSING) }
									},
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
						horizontalArrangement = Arrangement.spacedBy(12.dp),
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
								onFocused = {
									viewModel.setFocused(RecordingFocus.Recording(item, now))
									bgDebouncer.submit { backgroundService.setBackground(item, BlurContext.BROWSING) }
								},
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
			fontSize = 18.sp,
			fontWeight = FontWeight.SemiBold,
			color = Color.White,
			modifier = Modifier.padding(start = 60.dp, top = 12.dp, bottom = 8.dp),
		)
	}

	/** A show's recordings, newest first, in place of the rows; Back returns to the rows. */
	@Composable
	private fun ShowPage(
		show: RecordedShow,
		uiState: RecordingsBrowseUiState,
		format: RecordingFormat,
		onMenu: (BaseItemDto) -> Unit,
		modifier: Modifier = Modifier,
	) {
		val bgDebouncer = rememberFocusDebouncer()
		val firstFocus = remember { FocusRequester() }
		Column(modifier = modifier.fillMaxWidth()) {
			Text(
				text = show.title + " · " + pluralStringResource(R.plurals.lbl_show_recordings, show.items.size, show.items.size),
				fontSize = 18.sp,
				fontWeight = FontWeight.SemiBold,
				color = Color.White,
				modifier = Modifier.padding(start = 60.dp, top = 12.dp, bottom = 8.dp),
			)
			LazyVerticalGrid(
				columns = GridCells.Adaptive(212.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp),
				contentPadding = PaddingValues(start = 60.dp, end = 60.dp, top = 8.dp, bottom = 16.dp),
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
						onFocused = {
							viewModel.setFocused(RecordingFocus.Recording(item, now))
							bgDebouncer.submit { backgroundService.setBackground(item, BlurContext.BROWSING) }
						},
						modifier = if (item.id == show.items.first().id) Modifier.focusRequester(firstFocus) else Modifier,
					)
				}
			}
		}
		LaunchedEffect(show.title) {
			runCatching { firstFocus.requestFocus() }
		}
	}

	@Composable
	private fun CardFrame(
		onClick: () -> Unit,
		onMenu: (() -> Unit)?,
		onFocused: () -> Unit,
		modifier: Modifier = Modifier,
		cardWidth: Int = 200,
		content: @Composable (isFocused: Boolean) -> Unit,
	) {
		val interactionSource = remember { MutableInteractionSource() }
		val isFocused by interactionSource.collectIsFocusedAsState()

		LaunchedEffect(isFocused) {
			if (isFocused) onFocused()
		}

		val scale = if (isFocused) 1.08f else 1.0f
		val alpha = if (isFocused) 1.0f else 0.75f

		Column(
			modifier = modifier
				.width(cardWidth.dp)
				.graphicsLayer {
					scaleX = scale
					scaleY = scale
					this.alpha = alpha
					// Fades without an offscreen layer per unfocused card.
					compositingStrategy = CompositingStrategy.ModulateAlpha
				}
				.onKeyEvent { event ->
					// The Menu key opens the action sheet; a long press of OK does too (onLongClick).
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
			horizontalAlignment = Alignment.Start,
		) {
			content(isFocused)
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
		cardWidth: Int = 200,
		cardHeight: Int = 112,
	) {
		CardFrame(onClick = onClick, onMenu = onMenu, onFocused = onFocused, modifier = modifier, cardWidth = cardWidth) { isFocused ->
			Box(
				modifier = Modifier
					.width(cardWidth.dp)
					.height(cardHeight.dp)
					.clip(RoundedCornerShape(4.dp))
					.background(Color.White.copy(alpha = if (isFocused) 0.14f else 0.06f)),
			) {
				CardImage(item, cardWidth, cardHeight)
				if (recordingNow != null) {
					RecChip(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
				}
				val resume = recordingResumeTicks(item)
				val runtime = item.runTimeTicks
				if (resume != null && runtime != null && runtime > 0) {
					val fraction = (resume.toFloat() / runtime).coerceIn(0.02f, 1f)
					Box(
						modifier = Modifier
							.align(Alignment.BottomStart)
							.fillMaxWidth()
							.height(3.dp)
							.background(Color.Black.copy(alpha = 0.5f)),
					) {
						Box(
							modifier = Modifier
								.fillMaxWidth(fraction)
								.fillMaxHeight()
								.background(JellyfinBlue),
						)
					}
				}
			}

			Spacer(modifier = Modifier.height(5.dp))

			Text(
				text = item.name ?: "",
				fontSize = 13.sp,
				fontWeight = FontWeight.Medium,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			val context = LocalContext.current
			val subtitle = remember(item, recordingNow, format) {
				cardLine2(item, recordingNow, format) { context.getString(R.string.lbl_recording_since, it) }
			}
			if (subtitle.isNotEmpty()) {
				Text(
					text = subtitle,
					fontSize = 11.sp,
					fontWeight = FontWeight.Normal,
					color = if (recordingNow != null) RecordingRed else Color.White.copy(alpha = 0.5f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
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
		cardWidth: Int = 200,
		cardHeight: Int = 112,
	) {
		CardFrame(onClick = onClick, onMenu = null, onFocused = onFocused, modifier = modifier, cardWidth = cardWidth) { isFocused ->
			Box(
				modifier = Modifier
					.width(cardWidth.dp)
					.height(cardHeight.dp)
					.clip(RoundedCornerShape(4.dp))
					.background(Color.White.copy(alpha = if (isFocused) 0.14f else 0.06f)),
			) {
				CardImage(show.newest, cardWidth, cardHeight)
				Box(
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(6.dp)
						.clip(RoundedCornerShape(4.dp))
						.background(Color.Black.copy(alpha = 0.65f))
						.padding(horizontal = 7.dp, vertical = 2.dp),
				) {
					Text(text = show.items.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
				}
			}

			Spacer(modifier = Modifier.height(5.dp))

			Text(
				text = show.title,
				fontSize = 13.sp,
				fontWeight = FontWeight.Medium,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			val newest = recordingAirStart(show.newest)
			Text(
				text = listOfNotNull(
					pluralStringResource(R.plurals.lbl_show_recordings, show.items.size, show.items.size),
					newest?.let { stringResource(R.string.lbl_show_newest, format.day(it)) },
				).joinToString(" · "),
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}

	@Composable
	private fun CardImage(item: BaseItemDto, cardWidth: Int, cardHeight: Int) {
		val px = rememberCardPx(cardWidth, cardHeight)
		val imageUrl = remember(item.id, item.imageTags, px) { getRecordingImageUrl(item, px) }
		if (imageUrl != null) {
			AsyncImage(
				model = imageUrl,
				contentDescription = item.name,
				modifier = Modifier.fillMaxSize(),
				contentScale = ContentScale.Crop,
			)
		} else {
			Box(
				modifier = Modifier.fillMaxSize(),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_record),
					contentDescription = null,
					modifier = Modifier.size(48.dp),
					tint = Color.White.copy(alpha = 0.2f),
				)
			}
		}
	}

	@Composable
	private fun RecordingsViewsRow() {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 4.dp),
		) {
			Text(
				text = stringResource(R.string.lbl_views),
				fontSize = 18.sp,
				fontWeight = FontWeight.SemiBold,
				color = Color.White,
				modifier = Modifier.padding(start = 60.dp, bottom = 8.dp),
			)

			LazyRow(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				contentPadding = PaddingValues(horizontal = 60.dp),
			) {
				item {
					RecordingsNavButton(
						label = stringResource(R.string.lbl_schedule),
						iconRes = R.drawable.ic_tv_timer,
						onClick = {
							navigationRepository.navigate(Destinations.liveTvSchedule)
						},
					)
				}
				item {
					RecordingsNavButton(
						label = stringResource(R.string.lbl_series_recordings),
						iconRes = R.drawable.ic_record_series,
						onClick = {
							navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
						},
					)
				}
			}
		}
	}

	@Composable
	private fun RecordingsNavButton(
		label: String,
		iconRes: Int,
		onClick: () -> Unit,
	) {
		val interactionSource = remember { MutableInteractionSource() }
		val isFocused by interactionSource.collectIsFocusedAsState()

		val bgColor = when {
			isFocused -> Color.White.copy(alpha = 0.20f)
			else -> Color.White.copy(alpha = 0.08f)
		}

		Row(
			modifier = Modifier
				.clip(RoundedCornerShape(8.dp))
				.background(bgColor)
				.clickable(
					interactionSource = interactionSource,
					indication = null,
					onClick = onClick,
				)
				.padding(horizontal = 16.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = ImageVector.vectorResource(iconRes),
				contentDescription = label,
				modifier = Modifier.size(22.dp),
				tint = if (isFocused) Color.White else Color.White.copy(alpha = 0.6f),
			)

			Spacer(modifier = Modifier.width(8.dp))

			Text(
				text = label,
				fontSize = 14.sp,
				fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
				color = if (isFocused) Color.White else Color.White.copy(alpha = 0.7f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
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

	private fun getRecordingImageUrl(item: BaseItemDto, px: CardPx): String? {
		val thumb = item.itemImages[JellyfinImageType.THUMB]
		if (thumb != null) return thumb.getCardUrl(viewModel.api, px.width, px.height)

		val primary = item.itemImages[JellyfinImageType.PRIMARY]
		if (primary != null) return primary.getCardUrl(viewModel.api, px.width, px.height)

		val parentThumb = item.parentImages[JellyfinImageType.THUMB]
		if (parentThumb != null) return parentThumb.getCardUrl(viewModel.api, px.width, px.height)

		val parentPrimary = item.parentImages[JellyfinImageType.PRIMARY]
		if (parentPrimary != null) return parentPrimary.getCardUrl(viewModel.api, px.width, px.height)

		return null
	}

	private fun launchItem(item: BaseItemDto) {
		val rowItem = BaseItemDtoBaseRowItem(item)
		itemLauncher.launch(rowItem, null, requireContext())
	}
}
