package org.jellyfin.androidtv.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch

/**
 * Extension functions for lifecycle-aware coroutine operations.
 * Provides convenient methods for collecting flows safely within lifecycle boundaries.
 */

/**
 * Collect a flow safely within the lifecycle of the owner.
 * The collection starts when the lifecycle reaches the specified state and stops when it drops below.
 *
 * @param lifecycleState The minimum lifecycle state for collection (default: STARTED)
 * @param collector The collector to process flow emissions
 */
fun <T> Flow<T>.collectIn(
	lifecycleOwner: LifecycleOwner,
	lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
	collector: FlowCollector<T>
) {
	lifecycleOwner.lifecycleScope.launch {
		lifecycleOwner.repeatOnLifecycle(lifecycleState) {
			collect(collector)
		}
	}
}

/**
 * Collect a flow safely within the lifecycle of the owner with a simple lambda.
 * The collection starts when the lifecycle reaches the specified state and stops when it drops below.
 *
 * @param lifecycleState The minimum lifecycle state for collection (default: STARTED)
 * @param action The action to perform on each emission
 */
fun <T> Flow<T>.collectIn(
	lifecycleOwner: LifecycleOwner,
	lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
	action: suspend (T) -> Unit
) {
	lifecycleOwner.lifecycleScope.launch {
		lifecycleOwner.repeatOnLifecycle(lifecycleState) {
			collect { action(it) }
		}
	}
}

/**
 * Launch a coroutine in the lifecycle scope with repeatOnLifecycle.
 * The block executes when the lifecycle reaches the specified state and cancels when it drops below.
 *
 * @param lifecycleState The minimum lifecycle state for execution (default: STARTED)
 * @param block The suspending block to execute
 */
fun LifecycleOwner.launchWhenStarted(
	lifecycleState: Lifecycle.State = Lifecycle.State.STARTED,
	block: suspend CoroutineScope.() -> Unit
) {
	lifecycleScope.launch {
		repeatOnLifecycle(lifecycleState) {
			block()
		}
	}
}
