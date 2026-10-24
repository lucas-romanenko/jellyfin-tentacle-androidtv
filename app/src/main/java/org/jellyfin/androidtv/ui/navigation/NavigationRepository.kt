package org.jellyfin.androidtv.ui.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.Stack

/**
 * Repository for app navigation. This manages the screens/pages for the app.
 */
interface NavigationRepository {
	/**
	 * The current action to act on.
	 *
	 * @see NavigationAction
	 */
	val currentAction: SharedFlow<NavigationAction>

	/**
	 * The activity carried out [action]: it is no longer replayed. [currentAction] keeps the last
	 * action for a collector that wasn't there yet (MainActivity, after StartupActivity's reset),
	 * but MainActivity collects only while started: without this, every restart (the TV waking,
	 * the app back in front) ran the last action again, and the start-up reset built a new Home.
	 */
	fun onActionHandled(action: NavigationAction)

	/**
	 * The current fragment destination.
	 */
	val currentDestination: Destination.Fragment?

	/**
	 * Navigate to [destination].
	 *
	 * @see Destinations
	 */
	fun navigate(destination: Destination) = navigate(destination, false)

	/**
	 * Navigate to [destination].
	 *
	 * @see Destinations
	 */
	fun navigate(destination: Destination, replace: Boolean)

	/**
	 * Whether the [goBack] function will succeed or not.
	 *
	 * @see [goBack]
	 */
	val canGoBack: Boolean

	/**
	 * Go back to the previous fragment. The back stack does not consider other destination types.
	 *
	 * @see [canGoBack]
	 */
	fun goBack(): Boolean

	/**
	 * Reset navigation to the initial destination or a specific [Destination.Fragment].
	 *
	 * @param clearHistory Empty out the back stack
	 */
	fun reset(destination: Destination.Fragment? = null, clearHistory: Boolean)

	/**
	 * Reset navigation to the initial destination or a specific [Destination.Fragment] without clearing history.
	 */
	fun reset(destination: Destination.Fragment? = null) = reset(destination, false)

	/**
	 * Navigate to the home screen, preserving the existing home fragment if it's
	 * already in the back stack. This avoids recreating and reloading the home screen.
	 */
	fun goHome()
}

class NavigationRepositoryImpl(
	private val defaultDestination: Destination.Fragment,
) : NavigationRepository {
	// The screens above the first one. The first screen (the one a reset opened) is not in it: the
	// view (DestinationFragmentView) keeps that one as the root of its own history.
	private val fragmentHistory = Stack<Destination.Fragment>()

	// Whether that first screen is Home. It is not when the app opened on another screen (a title
	// from a launcher row, a voice search): popping to the first screen then doesn't reach Home.
	private var firstIsHome = true

	private val _currentAction = MutableSharedFlow<NavigationAction>(1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	override val currentAction = _currentAction.asSharedFlow()

	override val currentDestination: Destination.Fragment?
		get() = fragmentHistory.lastOrNull()

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun onActionHandled(action: NavigationAction) {
		// Only when it is still the last one: a newer action must still reach a later collector.
		if (_currentAction.replayCache.lastOrNull() === action) _currentAction.resetReplayCache()
	}

	override fun navigate(destination: Destination, replace: Boolean) {
		Timber.i("Navigating to $destination (via navigate function)")
		val action = when (destination) {
			is Destination.Fragment -> NavigationAction.NavigateFragment(destination, true, replace, false)
		}
		if (destination is Destination.Fragment) {
			if (replace && fragmentHistory.isNotEmpty()) fragmentHistory[fragmentHistory.lastIndex] = destination
			else fragmentHistory.push(destination)
		}
		_currentAction.tryEmit(action)
	}

	override val canGoBack: Boolean get() = fragmentHistory.isNotEmpty()

	override fun goBack(): Boolean {
		if (fragmentHistory.empty()) return false

		Timber.i("Navigating back")
		fragmentHistory.pop()
		_currentAction.tryEmit(NavigationAction.GoBack)
		return true
	}

	override fun reset(destination: Destination.Fragment?, clearHistory: Boolean) {
		fragmentHistory.clear()
		val actualDestination = destination ?: defaultDestination
		// Without clearHistory the view keeps its first screen and adds this one on top
		if (clearHistory) firstIsHome = actualDestination.fragment == defaultDestination.fragment
		_currentAction.tryEmit(NavigationAction.NavigateFragment(actualDestination, true, false, clearHistory))
		Timber.i("Navigating to $actualDestination (via reset, clearHistory=$clearHistory)")
	}

	override fun goHome() {
		if (!firstIsHome) {
			// The app opened on another screen: there is no Home to go back to, open one
			Timber.i("goHome: first screen isn't home, opening home")
			reset(defaultDestination, clearHistory = true)
			return
		}

		if (fragmentHistory.isEmpty()) {
			// Home is the screen in front — nothing to do
			Timber.i("goHome: already on home screen")
			return
		}

		// Pop every screen above the first one (home)
		fragmentHistory.clear()
		_currentAction.tryEmit(NavigationAction.PopToFirst)
		Timber.i("goHome: popping back to home screen")
	}
}

