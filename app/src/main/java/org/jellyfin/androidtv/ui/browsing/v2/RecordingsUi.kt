package org.jellyfin.androidtv.ui.browsing.v2

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.locale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Red of the "recording now" marks on the Recordings and Schedule screens. */
val RecordingRed = Color(0xFFE53935)

/** Dates and times as the Recordings and Schedule screens write them: "Fri 23 Oct", "8:00 PM" or "20:00". */
class RecordingFormat(context: Context) {
	private val day = DateTimeFormatter.ofPattern("EEE d MMM", context.locale)
	private val time = DateTimeFormatter.ofPattern(
		if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a",
		context.locale,
	)

	fun day(date: LocalDate): String = day.format(date)
	fun day(dateTime: LocalDateTime): String = day.format(dateTime)
	fun time(dateTime: LocalDateTime): String = time.format(dateTime)
	fun span(start: LocalDateTime, end: LocalDateTime?): String =
		if (end == null) time(start) else "${time(start)} – ${time(end)}"
}

@Composable
fun rememberRecordingFormat(): RecordingFormat {
	val context = LocalContext.current
	return remember(context) { RecordingFormat(context) }
}

/** One choice of the action sheet; [hint] is a small line under it. */
data class SheetAction(
	val label: String,
	val hint: String? = null,
	val onClick: () -> Unit,
)

/**
 * The small action sheet the Menu key or a long press of OK opens on a recording or a booked
 * programme. Picking a choice closes it first.
 */
@Composable
fun RecordingActionSheet(
	title: String,
	actions: List<SheetAction>,
	onDismiss: () -> Unit,
) {
	val firstFocus = remember { FocusRequester() }

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			Column(
				modifier = Modifier
					.widthIn(min = 340.dp, max = 460.dp)
					.clip(RoundedCornerShape(20.dp))
					.background(Color(0xE6141414))
					.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
					.padding(vertical = 16.dp),
			) {
				Text(
					text = title,
					fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.55f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
				)
				actions.forEachIndexed { index, action ->
					SheetRow(
						action = action,
						onClick = {
							onDismiss()
							action.onClick()
						},
						modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
					)
				}
			}
		}
	}

	LaunchedEffect(Unit) {
		runCatching { firstFocus.requestFocus() }
	}
}

@Composable
private fun SheetRow(
	action: SheetAction,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()

	Column(
		modifier = modifier
			.fillMaxWidth()
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.focusable(interactionSource = interactionSource)
			.background(if (isFocused) Color.White.copy(alpha = 0.14f) else Color.Transparent)
			.padding(horizontal = 24.dp, vertical = 11.dp),
	) {
		Text(
			text = action.label,
			fontSize = 17.sp,
			fontWeight = if (isFocused) FontWeight.W600 else FontWeight.W400,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		if (action.hint != null) {
			Spacer(modifier = Modifier.height(2.dp))
			Text(
				text = action.hint,
				fontSize = 12.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 2,
			)
		}
	}
}

/** A small chip on a card: "● REC". */
@Composable
fun RecChip(modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.clip(RoundedCornerShape(4.dp))
			.background(RecordingRed)
			.padding(horizontal = 6.dp, vertical = 2.dp),
	) {
		Text(text = "● REC", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
	}
}
