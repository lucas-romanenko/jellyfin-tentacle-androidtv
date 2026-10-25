package org.jellyfin.androidtv.ui.browsing.v2

import android.content.Context
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberCurrentTime
import org.jellyfin.androidtv.util.locale
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The guide's colours (res/values/colors.xml): the Recordings screen is built like the guide.
val GuideBackground = Color(0xFF110F1E)
val GuideAccent = Color(0xFF8B80FF)
val GuideChipBackground = Color(0xFF1D1A33)
val GuideChipStroke = Color(0xFF2F2B4D)
val GuideCellNow = Color(0xFF28234D)
val GuideTextMuted = Color(0xFF9B97B6)

// The cards' colours, from the Recordings mockup (recorded.png).
val CardArtBackground = Color(0xFF1A1D2E)
val CardTitleText = Color(0xFFE6E4F0)
val CardSubText = Color(0xFF8D8AA3)

/** Red of the "recording now" marks on the Recordings screen. */
val RecordingRed = Color(0xFFE53935)

/** Dates and times as the Recordings screen writes them: "Fri 23 Oct", "8:00 PM" or "20:00". */
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

/**
 * The top of the Recordings screen, as the guide's: the bold title, the Recorded | Scheduled |
 * Series switch built like the guide's Favorites | All (purple = shown, white = focused, the
 * count after the label), then Home and the clock on the right.
 */
@Composable
fun RecordingsTopBar(
	tabs: List<RecordingsTab>,
	selected: RecordingsTab,
	counts: Map<RecordingsTab, Int?>,
	onSelect: (RecordingsTab) -> Unit,
	onHome: () -> Unit,
	selectedChipFocus: FocusRequester,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = 24.dp, end = 32.dp, top = 19.dp)
			.height(34.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = stringResource(R.string.lbl_recordings),
			fontSize = 23.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
		)
		Spacer(modifier = Modifier.width(16.dp))
		Row(
			modifier = Modifier
				.height(34.dp)
				.clip(RoundedCornerShape(5.dp))
				.background(GuideChipBackground)
				.border(1.dp, GuideChipStroke, RoundedCornerShape(5.dp))
				.padding(2.dp),
			horizontalArrangement = Arrangement.spacedBy(2.dp),
		) {
			for (tab in tabs) {
				SwitchChip(
					label = stringResource(
						when (tab) {
							RecordingsTab.RECORDED -> R.string.lbl_recorded
							RecordingsTab.SCHEDULED -> R.string.lbl_scheduled
							RecordingsTab.SERIES -> R.string.lbl_recordings_series
						}
					),
					count = counts[tab],
					selected = tab == selected,
					onClick = { onSelect(tab) },
					modifier = if (tab == selected) Modifier.focusRequester(selectedChipFocus) else Modifier,
				)
			}
		}
		Spacer(modifier = Modifier.weight(1f))
		HomeButton(onClick = onHome)
		Spacer(modifier = Modifier.width(12.dp))
		val time by rememberCurrentTime()
		Text(text = time, fontSize = 20.sp, fontWeight = FontWeight.Light, color = Color.White)
	}
}

@Composable
private fun SwitchChip(
	label: String,
	count: Int?,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()
	val text = remember(label, count) {
		buildAnnotatedString {
			append(label)
			if (count != null) withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)) {
				append("  " + NumberFormat.getIntegerInstance().format(count))
			}
		}
	}
	Box(
		modifier = modifier
			.height(30.dp)
			.clip(RoundedCornerShape(3.dp))
			.background(
				when {
					isFocused -> Color.White
					selected -> GuideAccent
					else -> Color.Transparent
				}
			)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.padding(horizontal = 11.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = text,
			fontSize = 14.sp,
			fontWeight = FontWeight.Medium,
			color = if (isFocused) GuideBackground else Color.White,
			maxLines = 1,
		)
	}
}

@Composable
fun HomeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()
	Box(
		modifier = modifier
			.size(width = 40.dp, height = 32.dp)
			.clip(RoundedCornerShape(5.dp))
			.background(if (isFocused) Color.White else GuideChipBackground)
			.border(1.dp, if (isFocused) Color.White else GuideChipStroke, RoundedCornerShape(5.dp))
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_house),
			contentDescription = stringResource(R.string.home),
			modifier = Modifier.size(18.dp),
			tint = if (isFocused) GuideBackground else Color.White,
		)
	}
}

/**
 * The focused item, as the guide's header shows the focused programme: its picture on the left,
 * the name, a purple line with when and where, and a grey detail line.
 */
@Composable
fun GuideStyleHeader(
	imageUrl: String?,
	title: String,
	timeLine: String,
	detail: String?,
	detailColor: Color = GuideTextMuted,
	imageScale: ContentScale = ContentScale.Crop,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = 24.dp, end = 60.dp, top = 12.dp)
			.height(90.dp),
	) {
		Box(modifier = Modifier.size(width = 160.dp, height = 90.dp)) {
			if (imageUrl != null && imageScale == ContentScale.Fit) {
				// A logo: whole, on the cards' background
				AsyncImage(
					model = imageUrl,
					contentDescription = null,
					modifier = Modifier
						.fillMaxSize()
						.clip(RoundedCornerShape(4.dp))
						.background(CardArtBackground)
						.padding(horizontal = 24.dp, vertical = 12.dp),
					contentScale = ContentScale.Fit,
				)
			} else if (imageUrl != null) {
				AsyncImage(
					model = imageUrl,
					contentDescription = null,
					modifier = Modifier
						.fillMaxSize()
						.clip(RoundedCornerShape(4.dp)),
					contentScale = imageScale,
				)
			}
		}
		Column(modifier = Modifier.padding(start = 16.dp)) {
			Text(
				text = title,
				fontSize = 20.sp,
				fontWeight = FontWeight.Medium,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			if (timeLine.isNotEmpty()) {
				Text(
					text = timeLine,
					fontSize = 14.sp,
					color = GuideAccent,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 4.dp),
				)
			}
			if (!detail.isNullOrEmpty()) {
				Text(
					text = detail,
					fontSize = 14.sp,
					color = detailColor,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 6.dp),
				)
			}
		}
	}
}

/** One choice of the action sheet; [hint] is a small line under it. */
data class SheetAction(
	val label: String,
	val hint: String? = null,
	val onClick: () -> Unit,
)

/**
 * The small menu the Menu key or a long press of OK opens on a recording or a booked programme,
 * on the right like the mockup's. Picking a choice closes it first.
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
		// No dimming: the row it belongs to stays as it was, as in the mockup
		val window = (LocalView.current.parent as? DialogWindowProvider)?.window
		SideEffect { window?.setDimAmount(0f) }
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(end = 60.dp),
			contentAlignment = Alignment.CenterEnd,
		) {
			Column(
				modifier = Modifier
					.width(280.dp)
					.clip(RoundedCornerShape(6.dp))
					.background(GuideChipBackground)
					.border(1.dp, GuideChipStroke, RoundedCornerShape(6.dp))
					.padding(vertical = 6.dp),
			) {
				Text(
					text = title,
					fontSize = 11.sp,
					color = GuideTextMuted,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 2.dp),
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

	Column(modifier = Modifier.fillMaxWidth()) {
		Box(
			modifier = modifier
				.fillMaxWidth()
				.padding(horizontal = 4.dp)
				.clip(RoundedCornerShape(3.dp))
				.background(if (isFocused) GuideAccent else Color.Transparent)
				.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
				.padding(horizontal = 11.dp, vertical = 8.dp),
		) {
			Text(
				text = action.label,
				fontSize = 15.sp,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		if (action.hint != null) {
			Text(
				text = action.hint,
				fontSize = 11.sp,
				color = GuideTextMuted,
				maxLines = 2,
				modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 1.dp, bottom = 5.dp),
			)
		}
	}
}

/** A small chip on a card: "● REC". */
@Composable
fun RecChip(modifier: Modifier = Modifier) {
	Box(
		modifier = modifier
			.clip(RoundedCornerShape(3.dp))
			.background(RecordingRed)
			.padding(horizontal = 5.dp, vertical = 2.dp),
	) {
		Text(text = "● REC", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
	}
}

/**
 * A card as the Recordings mockup's: a purple outline when focused (no zoom, cheap on the Sony),
 * then the name and a grey line under it. [width] x [height] (dp) is the picture's size.
 */
@Composable
fun GuideCardFrame(
	width: Int,
	height: Int,
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
			.width(width.dp)
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
				.width(width.dp)
				.height(height.dp)
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
