package org.jellyfin.androidtv.ui.livetv.multiview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.v2.GuideAccent
import org.jellyfin.androidtv.ui.browsing.v2.GuideChipBackground
import org.jellyfin.androidtv.ui.browsing.v2.GuideChipStroke
import org.jellyfin.androidtv.ui.browsing.v2.GuideTextMuted
import org.jellyfin.androidtv.ui.browsing.v2.RecordingActionSheet

/** A tile's menu (the guide's action sheet), or the list of channels to swap it for. */
@Composable
fun MultiViewMenu(state: MenuState, onSwap: (Int, String) -> Unit, onDismiss: () -> Unit) {
	when (state) {
		is MenuState.Actions -> RecordingActionSheet(title = state.title, actions = state.actions, onDismiss = onDismiss)
		is MenuState.Swap -> SwapList(state, onSwap, onDismiss)
	}
}

@Composable
private fun SwapList(state: MenuState.Swap, onSwap: (Int, String) -> Unit, onDismiss: () -> Unit) {
	val firstFocus = remember { FocusRequester() }
	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		val window = (LocalView.current.parent as? DialogWindowProvider)?.window
		SideEffect { window?.setDimAmount(0f) }
		Box(modifier = Modifier.fillMaxSize().padding(end = 60.dp), contentAlignment = Alignment.CenterEnd) {
			Column(
				modifier = Modifier
					.width(320.dp)
					.clip(RoundedCornerShape(6.dp))
					.background(GuideChipBackground)
					.border(1.dp, GuideChipStroke, RoundedCornerShape(6.dp))
					.padding(vertical = 6.dp),
			) {
				Text(
					text = stringResource(R.string.multiview_swap_title),
					fontSize = 11.sp,
					color = GuideTextMuted,
					modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 2.dp),
				)
				if (state.choices.isEmpty()) {
					Text(
						text = stringResource(R.string.multiview_swap_none),
						fontSize = 14.sp,
						color = Color.White,
						modifier = Modifier.padding(15.dp),
					)
				}
				LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
					itemsIndexed(state.choices, key = { _, c -> c.itemId }) { i, choice ->
						val source = remember { MutableInteractionSource() }
						val focused by source.collectIsFocusedAsState()
						Box(
							modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
								.fillMaxWidth()
								.padding(horizontal = 4.dp)
								.clip(RoundedCornerShape(3.dp))
								.background(if (focused) GuideAccent else Color.Transparent)
								.clickable(interactionSource = source, indication = null) {
									onDismiss()
									onSwap(state.index, choice.itemId)
								}
								.padding(horizontal = 11.dp, vertical = 8.dp),
						) {
							Text(text = choice.label, fontSize = 15.sp, color = Color.White, maxLines = 1,
								overflow = TextOverflow.Ellipsis)
						}
					}
				}
			}
		}
	}
	LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
