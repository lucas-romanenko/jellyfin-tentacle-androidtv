package org.jellyfin.androidtv.ui.browsing.v2

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.eventhandling.RecordingChanges
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemdetail.programmeRecordClick
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.RecordingStatus
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.time.LocalDateTime

/**
 * Schedule (#203): what is booked to record, day by day (Today · Sat 24 Oct), one list row per
 * programme with its times, channel and state. OK opens the programme; Menu or a long press of OK
 * opens Programme details / Cancel this recording… / Cancel series….
 */
class ScheduleBrowseFragment : Fragment() {

	private val viewModel: ScheduleBrowseViewModel by viewModel()
	private val navigationRepository: NavigationRepository by inject()
	private val backgroundService: BackgroundService by inject()
	private val itemLauncher: ItemLauncher by inject()
	private val userRepository: UserRepository by inject()

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
			setContent { JellyfinTheme { ScheduleBrowseContent() } }
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
	private fun ScheduleBrowseContent() {
		val uiState by viewModel.uiState.collectAsState()
		var sheetFor by remember { mutableStateOf<ScheduleEntry?>(null) }

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
				ScheduleHeader()

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
					else -> {
						ScheduleAgenda(
							days = uiState.days,
							onMenu = { sheetFor = it },
							modifier = Modifier.weight(1f),
						)
					}
				}

				LibraryStatusBar(
					statusText = stringResource(R.string.lbl_schedule),
					counterText = if (uiState.isLoading) "" else uiState.days.sumOf { it.entries.size }.toString(),
				)
			}

			sheetFor?.let { entry ->
				ScheduleActionSheet(entry = entry, onDismiss = { sheetFor = null })
			}
		}
	}

	@Composable
	private fun ScheduleHeader() {
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
					text = stringResource(R.string.lbl_schedule),
					fontSize = 26.sp,
					fontWeight = FontWeight.Light,
					color = Color.White,
				)
			}

			Spacer(modifier = Modifier.height(10.dp))

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

	@Composable
	private fun ScheduleAgenda(
		days: List<ScheduleDay>,
		onMenu: (ScheduleEntry) -> Unit,
		modifier: Modifier = Modifier,
	) {
		val format = rememberRecordingFormat()
		val now = remember(days) { LocalDateTime.now() }

		LazyColumn(
			modifier = modifier.fillMaxWidth(),
			contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 24.dp),
		) {
			if (days.isEmpty()) {
				item(key = "empty") {
					Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 40.dp),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = stringResource(R.string.lbl_nothing_scheduled),
							fontSize = 16.sp,
							color = Color.White.copy(alpha = 0.5f),
						)
					}
				}
			}

			for (day in days) {
				item(key = "day:" + day.date, contentType = "dayHeader") {
					val label = when (day.daysFromToday) {
						0L -> stringResource(R.string.lbl_today) + " · " + format.day(day.date)
						1L -> stringResource(R.string.lbl_tomorrow) + " · " + format.day(day.date)
						else -> format.day(day.date)
					}
					Text(
						text = label,
						fontSize = 18.sp,
						fontWeight = FontWeight.SemiBold,
						color = Color.White,
						modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp),
					)
				}
				items(day.entries, key = { "timer:" + (it.timerId ?: it.item.id) }, contentType = { "scheduleRow" }) { entry ->
					ScheduleRow(
						entry = entry,
						now = now,
						format = format,
						onClick = { launchItem(entry.item) },
						onMenu = { onMenu(entry) },
					)
				}
			}
		}
	}

	/** "● 7:00 PM – 9:45 PM   Maple Leafs at Bruins   TSN 2   Recording now" */
	@Composable
	private fun ScheduleRow(
		entry: ScheduleEntry,
		now: LocalDateTime,
		format: RecordingFormat,
		onClick: () -> Unit,
		onMenu: () -> Unit,
	) {
		val interactionSource = remember { MutableInteractionSource() }
		val isFocused by interactionSource.collectIsFocusedAsState()
		val recordingNow = entry.isRecordingNow(now)
		val dim = if (isFocused) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.55f)

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 4.dp)
				.clip(RoundedCornerShape(8.dp))
				.background(if (isFocused) JellyfinBlue.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.07f))
				.onKeyEvent { event ->
					// The Menu key opens the action sheet; a long press of OK does too (onLongClick).
					val native = event.nativeKeyEvent
					if (native.action == KeyEvent.ACTION_UP && native.keyCode == KeyEvent.KEYCODE_MENU) {
						onMenu()
						true
					} else false
				}
				.combinedClickable(
					interactionSource = interactionSource,
					indication = null,
					onClick = onClick,
					onLongClick = onMenu,
				)
				.padding(horizontal = 20.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(modifier = Modifier.width(14.dp)) {
				if (recordingNow) {
					Box(
						modifier = Modifier
							.size(9.dp)
							.background(RecordingRed, CircleShape),
					)
				}
			}
			Text(
				text = format.span(entry.start, entry.end),
				fontSize = 16.sp,
				color = Color.White,
				maxLines = 1,
				modifier = Modifier.width(200.dp),
			)
			Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
				Text(
					text = entry.name,
					fontSize = 17.sp,
					fontWeight = FontWeight.Medium,
					color = Color.White,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				val sub = listOfNotNull(
					entry.episodeTitle?.takeIf { !it.equals(entry.name, ignoreCase = true) },
					if (entry.isSeries) stringResource(R.string.lbl_series_mark) else null,
				).joinToString(" · ")
				if (sub.isNotEmpty()) {
					Text(text = sub, fontSize = 13.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			}
			Text(
				text = entry.channelName.orEmpty(),
				fontSize = 16.sp,
				color = Color.White.copy(alpha = 0.9f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.width(200.dp),
			)
			val (state, stateColor) = when {
				recordingNow -> stringResource(R.string.lbl_recording_now) to (if (isFocused) Color.White else RecordingRed)
				entry.status == RecordingStatus.CONFLICTED_NOT_OK -> stringResource(R.string.lbl_conflict) to Color(0xFFFFB74D)
				entry.status == RecordingStatus.ERROR -> stringResource(R.string.lbl_recording_error) to Color(0xFFFFB74D)
				else -> stringResource(R.string.lbl_booked) to dim
			}
			Text(
				text = state,
				fontSize = 15.sp,
				color = stateColor,
				maxLines = 1,
				textAlign = TextAlign.End,
				modifier = Modifier.width(170.dp),
			)
		}
	}

	/** Programme details, Cancel this recording… and Cancel series… (for people who may manage recordings). */
	@Composable
	private fun ScheduleActionSheet(entry: ScheduleEntry, onDismiss: () -> Unit) {
		val context = LocalContext.current
		val canManage = Utils.canManageRecordings(userRepository.currentUser.value)
		val actions = buildList {
			add(SheetAction(context.getString(R.string.lbl_programme_details)) { launchItem(entry.item) })
			if (canManage && entry.timerId != null) {
				add(SheetAction(context.getString(R.string.lbl_cancel_this_recording)) { confirmCancel(entry) })
			}
			if (canManage && entry.seriesTimerId != null) {
				add(SheetAction(context.getString(R.string.lbl_cancel_series_ellipsis)) { confirmCancelSeries(entry) })
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
			cancel = { timerId -> viewModel.cancel(timerId, null, ::cancelled) },
		)
	}

	private fun confirmCancelSeries(entry: ScheduleEntry) {
		AlertDialog.Builder(requireContext())
			.setTitle(getString(R.string.lbl_cancel_series))
			.setMessage(getString(R.string.msg_cancel_entire_series))
			.setNegativeButton(R.string.lbl_no, null)
			.setPositiveButton(R.string.lbl_yes) { _, _ -> viewModel.cancel(null, entry.seriesTimerId, ::cancelled) }
			.show()
	}

	private fun cancelled(ok: Boolean) {
		val context = context ?: return
		Toast.makeText(context, if (ok) R.string.msg_recording_cancelled else R.string.msg_unable_to_cancel, Toast.LENGTH_SHORT).show()
	}

	private fun launchItem(item: BaseItemDto) {
		val rowItem = BaseItemDtoBaseRowItem(item)
		itemLauncher.launch(rowItem, null, requireContext())
	}
}
