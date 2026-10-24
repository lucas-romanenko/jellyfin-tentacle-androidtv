package org.jellyfin.androidtv.ui.browsing.v2

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.sdk.model.api.RecordingStatus
import java.time.LocalDateTime

// The Scheduled part's colours, from the mockup (scheduled.png).
private val ScheduleTimeText = Color(0xFFCFCBE6)
private val ScheduleSubText = Color(0xFFA9A5C4)
private val ScheduleSubTextFocused = Color(0xFFECE9FF)
private val ScheduleRecordingText = Color(0xFFFF6B6B)
private val ScheduleWarningText = Color(0xFFFFB74D)

/**
 * Scheduled: what is booked to record, day by day (Today · Sat 24 Oct), one row per programme
 * styled like the guide's cells, with its times, title, channel and state. OK opens the
 * programme; Menu or a long press of OK opens its menu.
 */
@Composable
fun ScheduledAgenda(
	days: List<ScheduleDay>,
	listState: LazyListState,
	firstFocus: FocusRequester,
	onOpen: (ScheduleEntry) -> Unit,
	onMenu: (ScheduleEntry) -> Unit,
	modifier: Modifier = Modifier,
) {
	val format = rememberRecordingFormat()
	val now = remember(days) { LocalDateTime.now() }
	val first = days.firstOrNull()?.entries?.firstOrNull()

	LazyColumn(
		state = listState,
		modifier = modifier.fillMaxWidth(),
		contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
	) {
		if (days.isEmpty()) {
			item(key = "empty") {
				EmptyLine(stringResource(R.string.lbl_nothing_scheduled))
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
					fontSize = 15.sp,
					fontWeight = FontWeight.Bold,
					color = Color.White,
					modifier = Modifier.padding(start = 4.dp, top = 13.dp, bottom = 6.dp),
				)
			}
			items(day.entries, key = { "timer:" + (it.timerId ?: it.item.id) }, contentType = { "scheduleRow" }) { entry ->
				ScheduleRow(
					entry = entry,
					now = now,
					format = format,
					onClick = { onOpen(entry) },
					onMenu = { onMenu(entry) },
					modifier = if (entry === first) Modifier.focusRequester(firstFocus) else Modifier,
				)
			}
		}
	}
}

/** "● 7:00 PM – 9:45 PM   Maple Leafs at Bruins   Sportsnet ON   Recording now" */
@Composable
private fun ScheduleRow(
	entry: ScheduleEntry,
	now: LocalDateTime,
	format: RecordingFormat,
	onClick: () -> Unit,
	onMenu: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()
	val recordingNow = entry.isRecordingNow(now)

	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(bottom = 4.dp)
			.clip(RoundedCornerShape(4.dp))
			.background(if (isFocused) GuideAccent else GuideCellNow)
			.onKeyEvent { event ->
				// The Menu key opens the menu; a long press of OK does too (onLongClick).
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
			.padding(horizontal = 14.dp, vertical = 9.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(modifier = Modifier.width(165.dp), verticalAlignment = Alignment.CenterVertically) {
			if (recordingNow) {
				Box(
					modifier = Modifier
						.padding(end = 6.dp)
						.size(8.dp)
						.background(RecordingRed, CircleShape),
				)
			}
			Text(
				text = format.span(entry.start, entry.end),
				fontSize = 14.sp,
				color = if (isFocused) Color.White else ScheduleTimeText,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
			Text(
				text = entry.name,
				fontSize = 15.sp,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			val sub = listOfNotNull(
				entry.episodeTitle?.takeIf { !it.equals(entry.name, ignoreCase = true) },
				if (entry.isSeries) stringResource(R.string.lbl_series_mark) else null,
			).joinToString(" · ")
			if (sub.isNotEmpty()) {
				Text(
					text = sub,
					fontSize = 11.sp,
					color = if (isFocused) ScheduleSubTextFocused else ScheduleSubText,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
		}
		Text(
			text = entry.channelName.orEmpty(),
			fontSize = 14.sp,
			color = if (isFocused) Color.White else ScheduleTimeText,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.width(150.dp),
		)
		val (state, stateColor) = when {
			recordingNow -> stringResource(R.string.lbl_recording_now) to ScheduleRecordingText
			entry.status == RecordingStatus.CONFLICTED_NOT_OK -> stringResource(R.string.lbl_conflict) to ScheduleWarningText
			entry.status == RecordingStatus.ERROR -> stringResource(R.string.lbl_recording_error) to ScheduleWarningText
			else -> stringResource(R.string.lbl_booked) to ScheduleSubText
		}
		Text(
			text = state,
			fontSize = 13.sp,
			color = if (isFocused) Color.White else stateColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			textAlign = TextAlign.End,
			modifier = Modifier.width(190.dp),
		)
	}
}

/** The one grey line an empty part shows. */
@Composable
fun EmptyLine(text: String) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 40.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(text = text, fontSize = 16.sp, color = GuideTextMuted)
	}
}
