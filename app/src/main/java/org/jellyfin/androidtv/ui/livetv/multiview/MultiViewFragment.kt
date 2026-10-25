package org.jellyfin.androidtv.ui.livetv.multiview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.MultiViewRepository
import org.jellyfin.androidtv.data.repository.MultiViewSession
import org.jellyfin.androidtv.data.repository.multiViewJson
import org.jellyfin.androidtv.ui.browsing.v2.SheetAction
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Multi-view: Tentacle's one picture of 2-4 channels, full screen. The D-pad moves the highlight
 * between tiles and the highlighted one is heard (its own sound track in the picture, so a switch
 * is a track change). OK plays that channel full screen (the normal player; its stream is already
 * open on the server) and Back there comes back here; Back here leaves and gives every connection
 * back. Hold OK (or Menu) on a tile: full screen, swap the channel, remove it.
 */
class MultiViewFragment : Fragment() {
	companion object {
		const val ARG_SESSION = "Session"
		private const val KEEPALIVE_MS = 15_000L
		private const val RETRY_MS = 1_500L
	}

	private val repository: MultiViewRepository by inject()
	private val navigationRepository: NavigationRepository by inject()
	private val playbackHelper: PlaybackHelper by inject()

	private lateinit var session: MultiViewSession
	private var highlighted = 0
	private var player: ExoPlayer? = null
	private var overlay: TileOverlay? = null
	private var status: TextView? = null
	private var spinner: View? = null
	private val menu = mutableStateOf<MenuState?>(null)
	private var keepAlive: Job? = null
	private var retries = 0
	private var busy = false
	private var closed = false

	/** Leaving is done once, on a scope that outlives this screen. */
	private val leaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		session = multiViewJson.decodeFromString(requireArguments().getString(ARG_SESSION)!!)
		highlighted = savedInstanceState?.getInt("highlighted") ?: 0
		startKeepAlive()
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putInt("highlighted", highlighted)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val context = requireContext()
		val root = object : FrameLayout(context) {
			override fun dispatchKeyEvent(event: KeyEvent): Boolean =
				if (onKey(event)) true else super.dispatchKeyEvent(event)
		}.apply {
			setBackgroundColor(Color.BLACK)
			isFocusable = true
			isFocusableInTouchMode = true
		}
		val playerView = PlayerView(context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			useController = false
			resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
			setShutterBackgroundColor(Color.BLACK)
			id = View.generateViewId()
		}
		root.addView(playerView)
		val tiles = TileOverlay(context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}
		root.addView(tiles)
		overlay = tiles
		spinner = ProgressBar(context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
		}.also(root::addView)
		status = TextView(context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(36) }
			setTextColor(Color.WHITE)
			textSize = 16f
			setPadding(dp(16), dp(8), dp(16), dp(8))
			background = ContextCompat.getDrawable(context, R.drawable.guide_chip_bg)
			isVisible = false
		}.also(root::addView)
		root.addView(ComposeView(context).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				val state = menu.value
				if (state != null) MultiViewMenu(state, onSwap = ::swap) { menu.value = null; view?.requestFocus() }
			}
		})
		showTiles()
		root.requestFocus()
		return root
	}

	override fun onStart() {
		super.onStart()
		play()
	}

	override fun onStop() {
		super.onStop()
		player?.release()
		player = null
	}

	override fun onDestroyView() {
		super.onDestroyView()
		overlay = null
		status = null
		spinner = null
	}

	override fun onDestroy() {
		super.onDestroy()
		leave()
	}

	// ── The picture ──────────────────────────────────────────────────────────────────

	@OptIn(UnstableApi::class)
	private fun play() {
		val playerView = (view as? ViewGroup)?.getChildAt(0) as? PlayerView ?: return
		player?.release()
		spinner?.isVisible = true
		val extractors = DefaultExtractorsFactory()
		val exo = ExoPlayer.Builder(requireContext())
			.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultHttpDataSource.Factory(), extractors))
			.build()
		exo.addListener(object : Player.Listener {
			override fun onTracksChanged(tracks: Tracks) = applyAudio()

			override fun onPlaybackStateChanged(state: Int) {
				if (state == Player.STATE_READY) {
					spinner?.isVisible = false
					retries = 0
				}
				if (state == Player.STATE_ENDED) retry("ended")
			}

			override fun onPlayerError(error: PlaybackException) = retry(error.errorCodeName)
		})
		exo.setMediaItem(MediaItem.Builder().setUri(repository.streamUrl(session.id)).setMimeType(MimeTypes.VIDEO_MP2T).build())
		exo.playWhenReady = true
		exo.prepare()
		playerView.player = exo
		player = exo
	}

	/** The picture stopped: open it again (the server keeps its last seconds), a few times. */
	private fun retry(why: String) {
		Timber.i("Multi-view picture stopped ($why), retry ${retries + 1}")
		if (closed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
		if (++retries > 3) {
			say(getString(R.string.multiview_lost))
			return
		}
		viewLifecycleOwner.lifecycleScope.launch {
			delay(RETRY_MS)
			if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) play()
		}
	}

	/** Only the highlighted tile's sound track plays. */
	private fun applyAudio() {
		val exo = player ?: return
		val audio = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
		val track = session.tiles.getOrNull(highlighted)?.audioTrack ?: return
		val group = audio.getOrNull(track) ?: return
		if (group.isSelected) return
		exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
			.build()
	}

	private fun showTiles() {
		overlay?.set(session, highlighted)
	}

	// ── Keys ─────────────────────────────────────────────────────────────────────────

	private var longPressed = false

	private fun onKey(event: KeyEvent): Boolean {
		if (menu.value != null) return false
		val down = event.action == KeyEvent.ACTION_DOWN
		when (event.keyCode) {
			KeyEvent.KEYCODE_BACK -> {
				if (!down) {
					leave()
					navigationRepository.goBack()
				}
				return true
			}
			KeyEvent.KEYCODE_DPAD_LEFT -> return down.also { if (down) move(TileGrid.Direction.LEFT) }
			KeyEvent.KEYCODE_DPAD_RIGHT -> return down.also { if (down) move(TileGrid.Direction.RIGHT) }
			KeyEvent.KEYCODE_DPAD_UP -> return down.also { if (down) move(TileGrid.Direction.UP) }
			KeyEvent.KEYCODE_DPAD_DOWN -> return down.also { if (down) move(TileGrid.Direction.DOWN) }
			KeyEvent.KEYCODE_MENU -> {
				if (!down) openMenu()
				return true
			}
			KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				if (down) {
					if (event.repeatCount == 0) {
						longPressed = false
						event.startTracking()
					} else if (event.isLongPress || event.repeatCount > 0 && event.eventTime - event.downTime > 500) {
						longPressed = true
					}
					return true
				}
				// The menu opens on the release, so the release can't pick its first choice.
				if (longPressed || event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS != 0) openMenu() else fullScreen()
				longPressed = false
				return true
			}
		}
		return false
	}

	private fun move(direction: TileGrid.Direction) {
		val next = TileGrid.move(highlighted, session.tiles.size, direction)
		if (next == highlighted) return
		highlighted = next
		showTiles()
		applyAudio()
	}

	/** The highlighted channel, full screen, through the normal player. */
	private fun fullScreen(index: Int = highlighted) {
		val tile = session.tiles.getOrNull(index) ?: return
		val id = tile.itemId.toUUIDOrNull() ?: return
		highlighted = index
		TvManager.setLastLiveTvChannel(id)
		playbackHelper.retrieveAndPlay(id, false, requireContext())
	}

	// ── Menu: full screen, swap, remove ──────────────────────────────────────────────

	private fun openMenu() {
		val tile = session.tiles.getOrNull(highlighted) ?: return
		val index = highlighted
		val actions = buildList {
			add(SheetAction(getString(R.string.multiview_full_screen)) { fullScreen(index) })
			add(SheetAction(getString(R.string.multiview_swap)) { menu.value = MenuState.Swap(index, swapChoices()) })
			if (session.tiles.size > MultiViewPicks.MIN_TILES)
				add(SheetAction(getString(R.string.multiview_remove)) { remove(index) })
		}
		menu.value = MenuState.Actions(tile.name, actions)
	}

	/** Favourite channels (else every channel) not already on screen. */
	private fun swapChoices(): List<SwapChoice> {
		val favourites = TvManager.getFavoriteChannels().orEmpty()
		val channels = favourites.ifEmpty { TvManager.getCachedAllChannels().orEmpty() }
		return channels
			.filter { ch -> session.tiles.none { sameId(it.itemId, ch.id.toString()) } }
			.map { SwapChoice(it.id.toString().replace("-", ""), listOfNotNull(it.number, it.name).joinToString("  ")) }
	}

	private fun remove(index: Int) {
		val ids = withoutTile(session.tiles.map { it.itemId }, index) ?: return
		change(ids, if (highlighted >= ids.size) ids.size - 1 else highlighted)
	}

	private fun swap(index: Int, newId: String) {
		val ids = withSwapped(session.tiles.map { it.itemId }, index, newId) ?: return
		change(ids, index)
	}

	private fun change(ids: List<String>, nextHighlight: Int) {
		if (busy) return
		busy = true
		spinner?.isVisible = true
		lifecycleScope.launch {
			val answer = repository.change(session.id, ids)
			busy = false
			val next = answer.session
			if (next == null) {
				spinner?.isVisible = false
				say(answer.error ?: getString(R.string.multiview_failed))
				return@launch
			}
			session = next
			highlighted = nextHighlight.coerceIn(0, next.tiles.size - 1)
			requireArguments().putString(ARG_SESSION, multiViewJson.encodeToString(next))
			showTiles()
			retries = 0
			if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) play()
		}
	}

	// ── Staying alive, leaving ───────────────────────────────────────────────────────

	/** While this screen or a channel from it is on (not when the app is in the background). */
	private fun startKeepAlive() {
		keepAlive = lifecycleScope.launch {
			while (isActive) {
				delay(KEEPALIVE_MS)
				val activityOn = activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
				if (!activityOn || busy) continue
				val answer = repository.keepAlive(session.id)
				if (answer.session == null && !answer.unreachable && answer.status == 404) {
					// The server closed it (restarted, or the app was away too long).
					closed = true
					if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
						Toast.makeText(requireContext(), R.string.multiview_ended, Toast.LENGTH_LONG).show()
						navigationRepository.goBack()
					}
					return@launch
				}
				answer.session?.let { fresh ->
					if (fresh.tiles.map { it.live } != session.tiles.map { it.live }) {
						session = fresh
						showTiles()
					}
				}
			}
		}
	}

	private fun leave() {
		if (closed) return
		closed = true
		keepAlive?.cancel()
		player?.release()
		player = null
		val id = session.id
		leaveScope.launch { withContext(NonCancellable) { repository.close(id) } }
	}

	private fun say(text: String) {
		status?.apply {
			this.text = text
			isVisible = true
			removeCallbacks(hideStatus)
			postDelayed(hideStatus, 5_000)
		}
	}

	private val hideStatus = Runnable { status?.isVisible = false }

	private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

	// ── The highlight and the names, drawn over the picture ──────────────────────────

	private class TileOverlay(context: Context) : View(context) {
		private var session: MultiViewSession? = null
		private var highlighted = 0
		private val density = resources.displayMetrics.density
		private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			style = Paint.Style.STROKE
			strokeWidth = 4 * density
			color = ContextCompat.getColor(context, R.color.tentacle_accent)
		}
		private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textSize = 14 * density
			isFakeBoldText = true
		}
		private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3110F1E.toInt() }
		private val dim = Paint().apply { color = 0x66000000 }
		private val rect = RectF()

		fun set(session: MultiViewSession, highlighted: Int) {
			this.session = session
			this.highlighted = highlighted
			invalidate()
		}

		override fun onDraw(canvas: Canvas) {
			val s = session ?: return
			// The picture is fitted 16:9 inside the view.
			val scale = minOf(width.toFloat() / s.width, height.toFloat() / s.height)
			val pw = s.width * scale
			val ph = s.height * scale
			val ox = (width - pw) / 2
			val oy = (height - ph) / 2
			s.tiles.forEachIndexed { i, tile ->
				val box = tileBox(tile.x, tile.y, tile.width, tile.height, s.width, s.height)
				rect.set(ox + box.left * pw, oy + box.top * ph, ox + box.right * pw, oy + box.bottom * ph)
				val on = i == highlighted
				if (!on) canvas.drawRect(rect, dim)
				val text = (if (on && tile.hasAudio) "♪  " else "") + tile.name +
					if (!tile.live) " · " + context.getString(R.string.multiview_stopped) else ""
				val pad = 8 * density
				val tw = label.measureText(text)
				canvas.drawRoundRect(rect.left + pad, rect.top + pad, rect.left + pad * 3 + tw,
					rect.top + pad * 2 + label.textSize + 4 * density, 4 * density, 4 * density, labelBg)
				canvas.drawText(text, rect.left + pad * 2, rect.top + pad * 1.5f + label.textSize, label)
				if (on) {
					val inset = frame.strokeWidth / 2
					canvas.drawRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, frame)
				}
			}
		}
	}
}

sealed class MenuState {
	data class Actions(val title: String, val actions: List<SheetAction>) : MenuState()
	data class Swap(val index: Int, val choices: List<SwapChoice>) : MenuState()
}

data class SwapChoice(val itemId: String, val label: String)
