package org.jellyfin.androidtv.ui.itemdetail.v2

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify

/**
 * A YouTube trailer (a movie's Trailer button, Discover's Trailer) plays on its own screen,
 * TrailerPlayerFragment. OK pauses it and Left / Right skip 10 s, but its player view has no
 * controller (useController = false): a paused trailer shows no timeline, and a skip lands
 * without showing where. The screen's real key handler is pressed here, and each press must
 * bring up the timeline (the player view's controller).
 */
class TrailerPlayerTimelineTests : FunSpec({
	// ExoPlayer's static setup asks whether this is an emulator, from Build fields the JVM leaves null
	beforeSpec {
		mockkStatic(Util::class)
		every { Util.isRunningOnEmulator() } returns false
	}
	afterSpec { unmockkAll() }

	fun key(code: Int) = mockk<KeyEvent> {
		every { action } returns KeyEvent.ACTION_DOWN
		every { keyCode } returns code
	}

	/** The screen as onCreateView leaves it: [player] playing at 30 s, its root view (the key handler) and its player view. */
	class Screen(val root: FrameLayout, val player: ExoPlayer, val playerView: PlayerView?)

	fun screen(): Screen {
		// Built without its constructor: the test needs only its fields and the key handler
		val fragment = mockk<TrailerPlayerFragment>(relaxed = true)
		val player = mockk<ExoPlayer>(relaxed = true) {
			every { playWhenReady } returns true
			every { currentPosition } returns 30_000L
		}
		TrailerPlayerFragment::class.java.getDeclaredField("player").apply { isAccessible = true }.set(fragment, player)
		// The player view the timeline belongs to, wherever the screen keeps it
		val viewField = TrailerPlayerFragment::class.java.declaredFields.firstOrNull { PlayerView::class.java.isAssignableFrom(it.type) }
		val playerView = viewField?.let { field ->
			mockk<PlayerView>(relaxed = true).also { field.isAccessible = true; field.set(fragment, it) }
		}
		// onCreateView's root: a FrameLayout whose dispatchKeyEvent handles the remote
		val rootClass = Class.forName("org.jellyfin.androidtv.ui.itemdetail.v2.TrailerPlayerFragment\$onCreateView\$root\$1")
		val constructor = rootClass.declaredConstructors.single().apply { isAccessible = true }
		val args = constructor.parameterTypes.map { type ->
			when {
				type == TrailerPlayerFragment::class.java -> fragment
				Context::class.java.isAssignableFrom(type) -> mockk<Context>(relaxed = true)
				PlayerView::class.java.isAssignableFrom(type) -> playerView ?: fail("the key handler takes a player view the screen doesn't keep")
				else -> fail("unexpected key handler parameter $type")
			}
		}
		return Screen(constructor.newInstance(*args.toTypedArray()) as FrameLayout, player, playerView)
	}

	fun Screen.timeline(): PlayerView = playerView
		?: fail("the trailer screen has no timeline to show: its player view is built with useController = false and not kept")

	test("OK pauses the trailer and shows the timeline") {
		val screen = screen()
		screen.root.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_CENTER)) shouldBe true
		verify { screen.player.playWhenReady = false }
		withClue("pausing must bring up the timeline") { verify { screen.timeline().showController() } }
	}

	test("Play / Pause on the remote shows the timeline too") {
		val screen = screen()
		screen.root.dispatchKeyEvent(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) shouldBe true
		verify { screen.player.playWhenReady = false }
		verify { screen.timeline().showController() }
	}

	test("Right skips 10 s and shows where it landed") {
		val screen = screen()
		screen.root.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_RIGHT)) shouldBe true
		verify { screen.player.seekTo(40_000L) }
		verify { screen.timeline().showController() }
	}

	test("Left skips back 10 s and shows where it landed") {
		val screen = screen()
		screen.root.dispatchKeyEvent(key(KeyEvent.KEYCODE_DPAD_LEFT)) shouldBe true
		verify { screen.player.seekTo(20_000L) }
		verify { screen.timeline().showController() }
	}
})
