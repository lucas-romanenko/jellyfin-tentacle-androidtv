package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.acra.ACRA
import org.jellyfin.androidtv.util.apiclient.ioCall
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.eventhandling.SocketHandler
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.service.SelfUpdateMode
import org.jellyfin.androidtv.data.service.UpdateCheckerService
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.background.UpdateCheckWorker
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.jellyfin.androidtv.ui.home.HomeTiming
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Suppress("unused")
class JellyfinApplication : Application(), SingletonImageLoader.Factory {
	override fun onCreate() {
		HomeTiming.mark("Application onCreate")
		super.onCreate()

		// Don't run in ACRA service
		if (ACRA.isACRASenderServiceProcess()) return

		val notificationsRepository by inject<NotificationsRepository>()
		notificationsRepository.addDefaultNotifications()
		HomeTiming.mark("Application onCreate done")
	}

	/**
	 * Provide the Koin-configured ImageLoader as the singleton for Coil.
	 * This ensures all Coil Compose components (AsyncImage, rememberAsyncImagePainter, etc.)
	 * use the ImageLoader with proper authentication, caching, and decoders.
	 */
	override fun newImageLoader(context: Context): ImageLoader {
		val imageLoader by inject<ImageLoader>()
		return imageLoader
	}
	/**
	 * Called from the StartupActivity when the user session is started. Starts its work in the
	 * background and returns at once: Home doesn't wait for any of it (awaiting it held the
	 * handover to Home for about a second on a TV).
	 */
	fun onSessionStart() {
		ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) { startSessionWork() }
	}

	private suspend fun startSessionWork() = coroutineScope {
		val workManager by inject<WorkManager>()
		val socketListener by inject<SocketHandler>()
		val serverRepository by inject<ServerRepository>()
		val updateCheckerService by inject<UpdateCheckerService>()

		launch { serverRepository.loadStoredServers() }

		// The download of an earlier install attempt is no longer needed
		launch { updateCheckerService.deleteOldDownload() }

		launch {
			workManager.cancelAllWork().await()

			// The launcher channels refresh a few minutes after start, not during it: re-enqueued
			// without a delay, the worker ran at every app start and fetched Next Up and Continue
			// Watching while Home was loading the same rows.
			workManager.enqueueUniquePeriodicWork(
				LeanbackChannelWorker.PERIODIC_UPDATE_REQUEST_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				PeriodicWorkRequestBuilder<LeanbackChannelWorker>(1, TimeUnit.HOURS)
					.setInitialDelay(3, TimeUnit.MINUTES)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
					.build()
			).await()

			// Daily new-version check (github flavor release builds; secondary on TV, where
			// launchers rarely show notifications: the in-app dialog is what people see)
			if (updateCheckerService.mode == SelfUpdateMode.ENABLED) {
				workManager.enqueueUniquePeriodicWork(
					UpdateCheckWorker.WORK_NAME,
					ExistingPeriodicWorkPolicy.KEEP,
					PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
						.setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
						.build()
				).await()
			}
		}

		launch { socketListener.updateSession() }
	}

	override fun attachBaseContext(base: Context?) {
		super.attachBaseContext(base)

		HomeTiming.mark("crash reporter init")
		TelemetryService.init(this)
		HomeTiming.mark("crash reporter init done")
	}
}
