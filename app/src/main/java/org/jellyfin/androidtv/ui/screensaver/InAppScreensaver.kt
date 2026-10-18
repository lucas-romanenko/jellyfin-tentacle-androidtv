package org.jellyfin.androidtv.ui.screensaver

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import org.jellyfin.androidtv.integration.dream.composable.DreamHost
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.util.isMediaSessionKeyEvent
import org.koin.androidx.compose.koinViewModel

@Composable
fun InAppScreensaver() {
	val interactionTrackerViewModel = koinViewModel<InteractionTrackerViewModel>()
	val visible by interactionTrackerViewModel.visible.collectAsState()

	DialogBase(
		visible = visible,
		onDismissRequest = {
			interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = false)
		},
		scrimColor = Color.Black,
		enterTransition = fadeIn(tween(1_000)),
		exitTransition = fadeOut(tween(1_000)),
		modifier = Modifier
			.fillMaxSize()
			// The screensaver is a dialog window, so MainActivity's key handling (any key closes
			// it) doesn't see its keys: only OK (the clickable) and Back closed it, the arrows did
			// nothing. Close it when any key is released; media keys pass through.
			.onPreviewKeyEvent { event ->
				if (event.nativeKeyEvent.isMediaSessionKeyEvent()) return@onPreviewKeyEvent false
				if (event.type == KeyEventType.KeyUp) {
					interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = true)
				}
				true
			}
			.clickable(
				interactionSource = remember { MutableInteractionSource() },
				indication = null,
			) {
				interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = false)
			}
	) {
		DreamHost()
	}
}
