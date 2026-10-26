package org.jellyfin.androidtv.ui.livetv.multiview

import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.MultiViewRepository
import org.jellyfin.androidtv.data.repository.multiViewJson
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import java.util.UUID

/**
 * Multi-view in the guide: the Multi-view button (next to Recordings) puts the guide in pick
 * mode. OK on a channel or one of its programmes ticks it (a numbered mark 1-4 on its row; OK
 * again unticks), the button becomes "2 of 4 · Start". Back goes straight to that button (no
 * scrolling back up the guide), Back again there cancels. The server says first
 * whether there are free connections (the button explains why not) and how many channels fit.
 */
class GuideMultiViewPick(
	private val fragment: Fragment,
	private val binding: LiveTvGuideBinding,
	private val channels: LinearLayout,
) {
	private val repository by fragment.inject<MultiViewRepository>()
	private val navigationRepository by fragment.inject<NavigationRepository>()
	private var picks = MultiViewPicks()
	private var serverMax = MultiViewPicks.MAX_TILES
	private var starting = false
	private val back = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			val start = binding.multiViewButton
			when (pickBack(start.hasFocus())) {
				PickBack.TO_START -> if (!start.requestFocus()) cancel()
				PickBack.CANCEL -> cancel()
			}
		}
	}

	val isActive: Boolean get() = back.isEnabled

	init {
		binding.multiViewButton.setOnClickListener { onButton() }
	}

	/** Back cancels pick mode before the guide's own Back (added after it, so it goes first). */
	fun attach(owner: LifecycleOwner) {
		fragment.requireActivity().onBackPressedDispatcher.addCallback(owner, back)
	}

	/** A row's mark, when the rows are (re)built. */
	fun decorate(header: GuideChannelHeader) {
		header.setPickNumber(if (isActive) picks.numberOf(key(header.channel.id)) else 0)
	}

	/** OK on a channel (its header, or a programme of it) while picking. */
	fun pick(channelId: UUID?) {
		if (channelId == null || starting) return
		val channel: BaseItemDto? = TvManager.findChannel(channelId)
		when (picks.toggle(key(channelId), channel?.name.orEmpty())) {
			MultiViewPicks.Toggle.FULL -> toast(
				if (serverMax < MultiViewPicks.MAX_TILES) fragment.getString(R.string.multiview_full_connections, serverMax)
				else fragment.getString(R.string.multiview_full, picks.max)
			)
			else -> Unit
		}
		refresh()
	}

	/** Play / Start while picking. */
	fun start() {
		if (!isActive || starting) return
		if (!picks.canStart) {
			toast(fragment.getString(R.string.multiview_pick_title))
			return
		}
		starting = true
		binding.multiViewButton.text = fragment.getString(R.string.multiview_starting)
		fragment.viewLifecycleOwner.lifecycleScope.launch {
			val answer = repository.start(picks.ids)
			starting = false
			val session = answer.session
			if (session == null) {
				refresh()
				toast(answer.error ?: fragment.getString(R.string.multiview_failed))
				return@launch
			}
			cancel()
			navigationRepository.navigate(Destinations.multiView(multiViewJson.encodeToString(session)))
		}
	}

	fun cancel() {
		back.isEnabled = false
		picks.clear()
		refresh()
	}

	private fun onButton() {
		if (isActive) return start()
		if (starting) return
		starting = true
		fragment.viewLifecycleOwner.lifecycleScope.launch {
			val state = repository.state()
			starting = false
			when {
				state == null || !state.available -> toast(state?.message?.takeIf { it.isNotBlank() }
					?: fragment.getString(R.string.multiview_unavailable))
				!state.canStart -> toast(state.message)
				else -> {
					serverMax = state.maxTiles.coerceIn(MultiViewPicks.MIN_TILES, MultiViewPicks.MAX_TILES)
					picks = MultiViewPicks(max = serverMax, min = state.minTiles.coerceAtLeast(MultiViewPicks.MIN_TILES))
					back.isEnabled = true
					refresh()
				}
			}
		}
	}

	private fun refresh() {
		val on = isActive
		binding.multiViewBar.isVisible = on
		binding.recordingsButton.isVisible = !on
		binding.multiViewButton.isSelected = on
		binding.multiViewButton.text = if (on) startLabel(picks.count, picks.max, picks.min)
		else fragment.getString(R.string.lbl_multiview)
		binding.multiViewPicked.text = if (picks.count == 0) fragment.getString(R.string.multiview_picked_none)
		else picks.ids.mapIndexed { i, id -> "${i + 1}  ${picks.nameOf(id)}" }.joinToString("    ")
		for (i in 0 until channels.childCount) {
			(channels.getChildAt(i) as? GuideChannelHeader)?.let(::decorate)
		}
	}

	private fun toast(text: String) {
		if (text.isNotBlank()) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_LONG).show()
	}

	private fun key(id: UUID) = id.toString().replace("-", "")
}
