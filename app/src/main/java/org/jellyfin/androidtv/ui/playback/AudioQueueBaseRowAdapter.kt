package org.jellyfin.androidtv.ui.playback

import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem

class AudioQueueBaseRowAdapter(
	private val playbackManager: PlaybackManager,
	lifecycleScope: LifecycleCoroutineScope,
) : MutableObjectAdapter<AudioQueueBaseRowItem>(CardPresenter(true, @Suppress("MagicNumber") 140)) {
	init {
		// One update per change, whichever of the three moved (a track change moves two or
		// three of them); the queue is read without blocking the main thread.
		lifecycleScope.launch {
			combine(
				playbackManager.queue.entry,
				playbackManager.queue.entries,
				playbackManager.state.playbackOrder,
			) { _, _, _ -> }
				.conflate()
				.collect { updateAdapter() }
		}
	}

	private suspend fun updateAdapter() {
		val currentItem = playbackManager.queue.entry.value?.let(::AudioQueueBaseRowItem)?.apply {
			playing = true
		}

		val upcomingItems = playbackManager.queue.peekNext(100)
			.mapNotNull { item -> item.takeIf { it.baseItem != null }?.let(::AudioQueueBaseRowItem) }

		val items = listOfNotNull(currentItem) + upcomingItems

		// Update item row: only cards whose item or playing state changed are rebound.
		replaceAll(
			items,
			areItemsTheSame = { old, new -> old.baseItem?.id == new.baseItem?.id },
			areContentsTheSame = { old, new -> old.playing == new.playing && old.baseItem == new.baseItem },
		)
	}
}
