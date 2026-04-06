package org.jellyfin.androidtv.ui.base

import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.util.getUserFeedbackManager

/**
 * Base Fragment class providing common functionality for all fragments.
 * Includes lifecycle-aware flow collection, error handling, and user feedback utilities.
 */
abstract class BaseFragment(@LayoutRes contentLayoutId: Int = 0) : Fragment(contentLayoutId) {
	
	/**
	 * Called when the view is created. Override this to set up your UI.
	 * This is called after onViewCreated but before collecting flows.
	 */
	protected open fun setupUI(view: View, savedInstanceState: Bundle?) {
		// Override in subclasses
	}
	
	/**
	 * Called to set up observers for ViewModels and flows.
	 * This is the ideal place to collect flows using the provided extension functions.
	 */
	protected open fun setupObservers() {
		// Override in subclasses
	}
	
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		setupUI(view, savedInstanceState)
		setupObservers()
	}
	
	/**
	 * Collect a flow safely within the fragment's lifecycle.
	 * Collection starts when the lifecycle reaches STARTED and stops when it drops below.
	 *
	 * @param flow The flow to collect
	 * @param action The action to perform on each emission
	 */
	protected fun <T> collectFlow(
		flow: Flow<T>,
		lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
		action: suspend (T) -> Unit
	) {
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(lifecycleState) {
				flow.collect { action(it) }
			}
		}
	}
	
	/**
	 * Show an error message to the user using the UserFeedbackManager.
	 *
	 * @param message The error message to display
	 * @param error Optional throwable for logging
	 */
	protected fun showError(message: String, error: Throwable? = null) {
		requireContext().getUserFeedbackManager().showError(message, error)
	}
	
	/**
	 * Show a success message to the user using the UserFeedbackManager.
	 *
	 * @param message The success message to display
	 */
	protected fun showSuccess(message: String) {
		requireContext().getUserFeedbackManager().showSuccess(message)
	}
	
	/**
	 * Show an informational message to the user using the UserFeedbackManager.
	 *
	 * @param message The message to display
	 */
	protected fun showMessage(message: String) {
		requireContext().getUserFeedbackManager().showMessage(message)
	}
	
	/**
	 * Show a long informational message to the user using the UserFeedbackManager.
	 *
	 * @param message The message to display
	 */
	protected fun showLongMessage(message: String) {
		requireContext().getUserFeedbackManager().showLongMessage(message)
	}
}
