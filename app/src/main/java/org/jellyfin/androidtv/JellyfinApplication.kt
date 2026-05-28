package org.jellyfin.androidtv

import android.app.Application
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA
import org.jellyfin.androidtv.util.apiclient.ioCall
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.eventhandling.SocketHandler
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.background.UpdateCheckWorker
import org.jellyfin.androidtv.telemetry.TelemetryService
import org.koin.android.ext.android.inject
import org.koin.core.qualifier.named
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Suppress("unused")
class JellyfinApplication : Application(), SingletonImageLoader.Factory {
	override fun onCreate() {
		super.onCreate()

		// Don't run in ACRA service
		if (ACRA.isACRASenderServiceProcess()) return

		val notificationsRepository by inject<NotificationsRepository>()
		notificationsRepository.addDefaultNotifications()
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
	 * Called from the StartupActivity when the user session is started.
	 */
	suspend fun onSessionStart() = withContext(Dispatchers.IO) {
		val workManager by inject<WorkManager>()
		val socketListener by inject<SocketHandler>()
		val serverRepository by inject<ServerRepository>()

		launch { serverRepository.loadStoredServers() }

		launch {
			workManager.cancelAllWork().await()

			workManager.enqueueUniquePeriodicWork(
				LeanbackChannelWorker.PERIODIC_UPDATE_REQUEST_NAME,
				ExistingPeriodicWorkPolicy.UPDATE,
				PeriodicWorkRequestBuilder<LeanbackChannelWorker>(1, TimeUnit.HOURS)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
					.build()
			).await()

			// Schedule update check worker (daily) — libre builds only
			if (BuildConfig.ENABLE_OTA_UPDATES) {
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

		TelemetryService.init(this)
	}
}
