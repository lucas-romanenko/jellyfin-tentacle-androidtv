package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.lang.ref.WeakReference

/**
 * Back in the guide's grid goes to the top row first (the Favorites | All switch, Recordings, …),
 * as Back on Home goes to the top bar (Lucas, 24 Oct: "if you scroll down a lot in the guide and
 * hit back … maybe you want to just go to your recordings"). Back again leaves the guide as before.
 * The grid keeps its place meanwhile, and the first press down from the top row goes back to the
 * cell Back left.
 */
class GuideBackToTop(
	private val fragment: Fragment,
	private val inGrid: (View) -> Boolean,
	private val topRow: () -> View?,
) {
	private var gridReturn: WeakReference<View>? = null

	fun attach(root: View) {
		val owner = fragment.viewLifecycleOwner
		fragment.requireActivity().onBackPressedDispatcher.addCallback(owner, object : OnBackPressedCallback(true) {
			override fun handleOnBackPressed() {
				val focus = fragment.activity?.currentFocus
				val top = topRow()
				if (focus != null && top != null && top.isShown && inGrid(focus)) {
					gridReturn = WeakReference(focus)
					top.requestFocus()
				} else {
					// Not in the grid: Back does what it did (leaves the guide)
					isEnabled = false
					fragment.requireActivity().onBackPressedDispatcher.onBackPressed()
					isEnabled = true
				}
			}
		})

		// Down from the top row after Back: the cell Back left, not whichever is nearest the switch
		val listener = ViewTreeObserver.OnGlobalFocusChangeListener { old, new ->
			val back = gridReturn?.get() ?: return@OnGlobalFocusChangeListener
			if (new == null || !inGrid(new)) return@OnGlobalFocusChangeListener
			gridReturn = null
			val fromTop = old != null && !inGrid(old)
			// Posted: this runs inside the focus change, before the nearest cell's own onFocusChanged(true),
			// so moving focus now left that cell drawn as focused (two purple cells on the Sony)
			if (fromTop && new !== back && back.isAttachedToWindow && back.isShown && inGrid(back)) {
				back.post { if (back.isAttachedToWindow && back.isShown) back.requestFocus() }
			}
		}
		var observer: ViewTreeObserver? = null
		owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
			when (event) {
				Lifecycle.Event.ON_START -> observer = root.viewTreeObserver.also { it.addOnGlobalFocusChangeListener(listener) }
				Lifecycle.Event.ON_STOP -> {
					observer?.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(listener)
					observer = null
				}
				else -> Unit
			}
		})
	}
}
