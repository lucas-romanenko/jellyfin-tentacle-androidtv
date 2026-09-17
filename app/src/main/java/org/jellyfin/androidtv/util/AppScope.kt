package org.jellyfin.androidtv.util

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Logs a failure instead of letting it reach the thread's uncaught handler.
 *
 * A coroutine launched in a detached scope has nowhere to propagate a failure to, so an ordinary
 * network timeout from the Jellyfin SDK (`TimeoutException`, `SocketTimeoutException`, …) killed
 * the whole process. Transient network trouble must degrade, not crash.
 */
val loggingExceptionHandler = CoroutineExceptionHandler { _, throwable ->
	Timber.e(throwable, "Uncaught exception in a background task")
}

/**
 * Process-lifetime scope for fire-and-forget UI work that must not take the app down.
 *
 * Prefer a lifecycle-bound scope (`lifecycleScope`, `viewModelScope`) wherever one exists — this
 * is for the call sites that were already detached, where the alternative is a bare
 * `CoroutineScope(Dispatchers.Main)` with no supervisor and no handler.
 */
val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + loggingExceptionHandler)
