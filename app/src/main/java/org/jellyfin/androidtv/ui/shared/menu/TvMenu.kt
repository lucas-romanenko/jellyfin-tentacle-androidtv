package org.jellyfin.androidtv.ui.shared.menu

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.v2.GuideAccent
import org.jellyfin.androidtv.ui.browsing.v2.GuideChipBackground
import org.jellyfin.androidtv.ui.browsing.v2.GuideChipStroke
import org.jellyfin.androidtv.ui.browsing.v2.GuideTextMuted

/** One choice of a [TvMenu]; [hint] is a small grey line under it. */
data class TvMenuAction(
	val label: String,
	val hint: String? = null,
	val onClick: () -> Unit,
)

/**
 * The app's one menu (a long press of OK or the Menu key on a card, a guide programme or channel, a
 * recording; and the confirmations): small, dark and rounded like the guide's header, the focused
 * choice purple, on the right of the screen so the row it belongs to stays in view. [message] is a
 * line of text under the title (a confirmation's question), [detail] a grey one under that. Picking a
 * choice closes the menu first.
 *
 * OK opens it while still held: [OkKeyGate] drops that press's repeats and release, so nothing in
 * the menu runs until OK is pressed again.
 */
@Composable
fun TvMenu(
	title: String?,
	actions: List<TvMenuAction>,
	onDismiss: () -> Unit,
	message: String? = null,
	detail: String? = null,
) {
	val firstFocus = remember { FocusRequester() }
	val gate = remember { OkKeyGate() }

	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		// No dimming: the row it belongs to stays as it was
		val window = (LocalView.current.parent as? DialogWindowProvider)?.window
		SideEffect { window?.setDimAmount(0f) }
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(end = 60.dp)
				.onPreviewKeyEvent { event ->
					val native = event.nativeKeyEvent
					gate.swallow(native.action, native.keyCode, native.repeatCount)
				},
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
				if (!title.isNullOrEmpty()) {
					Text(
						text = title,
						fontSize = 11.sp,
						color = GuideTextMuted,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 4.dp, bottom = 2.dp),
					)
				}
				if (!message.isNullOrEmpty()) {
					Text(
						text = message,
						fontSize = 14.sp,
						fontWeight = FontWeight.Medium,
						color = Color.White,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 2.dp, bottom = 6.dp),
					)
				}
				if (!detail.isNullOrEmpty()) {
					Text(
						text = detail,
						fontSize = 12.sp,
						color = GuideTextMuted,
						maxLines = 4,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 6.dp),
					)
				}
				actions.forEachIndexed { index, action ->
					TvMenuRow(
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
private fun TvMenuRow(
	action: TvMenuAction,
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

/**
 * [TvMenu] from screens built with Views (the guide, Home's Leanback rows): a ComposeView is put on
 * the activity's content for as long as the menu shows, and taken off when it closes. The focused
 * view keeps Android focus underneath and has it again after.
 */
object TvMenus {
	@JvmStatic
	@JvmOverloads
	fun show(
		activity: FragmentActivity,
		title: String?,
		actions: List<TvMenuAction>,
		message: String? = null,
		detail: String? = null,
		onDismiss: (() -> Unit)? = null,
	) {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
		val host = ComposeView(activity).apply {
			setViewTreeLifecycleOwner(activity)
			setViewTreeViewModelStoreOwner(activity)
			setViewTreeSavedStateRegistryOwner(activity)
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
			isFocusable = false
		}
		host.setContent {
			var open by remember { mutableStateOf(true) }
			if (open) {
				TvMenu(
					title = title,
					message = message,
					detail = detail,
					actions = actions,
					onDismiss = {
						open = false
						// Off the content after this frame: removing the view while its composition
						// runs the click would dispose it mid-call
						host.post { content.removeView(host) }
						onDismiss?.invoke()
					},
				)
			}
		}
		content.addView(host, ViewGroup.LayoutParams(0, 0))
	}

	/** A yes/no question ([message], [detail] under it) in the menu's style: [confirmLabel] first, then [cancelLabel]. */
	@JvmStatic
	@JvmOverloads
	fun confirm(
		activity: FragmentActivity,
		message: String,
		confirmLabel: String,
		cancelLabel: String,
		detail: String? = null,
		onConfirm: () -> Unit,
	) = show(
		activity = activity,
		title = null,
		message = message,
		detail = detail,
		actions = listOf(
			TvMenuAction(confirmLabel, onClick = onConfirm),
			TvMenuAction(cancelLabel, onClick = {}),
		),
	)
}
