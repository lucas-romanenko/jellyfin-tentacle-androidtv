package org.jellyfin.androidtv.ui.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.SelfUpdateMode
import org.jellyfin.androidtv.data.service.UpdateCheckResult
import org.jellyfin.androidtv.data.service.UpdateCheckerService
import org.jellyfin.androidtv.data.service.UpdateInfo
import org.jellyfin.androidtv.data.service.isUpdateSnoozed
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Background worker that checks for app updates
 */
class UpdateCheckWorker(
	context: Context,
	params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

	private val updateChecker by inject<UpdateCheckerService>()
	private val userPreferences by inject<UserPreferences>()

	companion object {
		const val NOTIFICATION_CHANNEL_ID = "app_updates"
		const val NOTIFICATION_ID = 1001
		const val WORK_NAME = "update_check_work"
		const val EXTRA_OPEN_UPDATES = "open_updates"
	}

	override suspend fun doWork(): Result {
		return try {
			// Debug builds and the playstore flavor never offer the release; the user can turn it off
			if (updateChecker.mode != SelfUpdateMode.ENABLED) return Result.success()
			if (!userPreferences[UserPreferences.updateNotificationsEnabled]) return Result.success()

			when (val result = updateChecker.checkForUpdate()) {
				is UpdateCheckResult.Available -> {
					// Notifications rarely show on TV launchers: Home offers it the next time it shows
					userPreferences[UserPreferences.updateFoundVersion] = result.info.version
					val snoozed = isUpdateSnoozed(
						version = result.info.version,
						snoozedVersion = userPreferences[UserPreferences.updateSnoozedVersion],
						snoozedAtMs = userPreferences[UserPreferences.updateSnoozedAt],
						nowMs = System.currentTimeMillis(),
					)
					if (!snoozed) {
						Timber.i("Update available: ${result.info.version}")
						showUpdateNotification(result.info)
					}
				}
				UpdateCheckResult.UpToDate -> {
					userPreferences[UserPreferences.updateFoundVersion] = ""
					Timber.d("No updates available")
				}
				is UpdateCheckResult.Failed -> Timber.w("Update check failed: ${result.reason}")
			}

			Result.success()
		} catch (e: Exception) {
			Timber.e(e, "Update check worker failed")
			Result.failure()
		}
	}

	private fun showUpdateNotification(updateInfo: UpdateInfo) {
		val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

		// Create notification channel for Android O+
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			val channel = NotificationChannel(
				NOTIFICATION_CHANNEL_ID,
				applicationContext.getString(R.string.notification_channel_updates),
				NotificationManager.IMPORTANCE_DEFAULT
			).apply {
				description = applicationContext.getString(R.string.notification_channel_updates_description)
			}
			notificationManager.createNotificationChannel(channel)
		}

		// Selecting it opens the app with the update dialog (StartupActivity -> UpdatePrompt)
		val intent = Intent(applicationContext, StartupActivity::class.java).apply {
			flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
			putExtra(EXTRA_OPEN_UPDATES, true)
		}

		val pendingIntent = PendingIntent.getActivity(
			applicationContext,
			0,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)

		// Build notification
		val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_tentacle)
			.setContentTitle(applicationContext.getString(R.string.notification_update_available_title))
			.setContentText(applicationContext.getString(R.string.notification_update_available_text, updateInfo.version))
			.setStyle(NotificationCompat.BigTextStyle()
				.bigText(applicationContext.getString(R.string.notification_update_available_text, updateInfo.version)))
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setContentIntent(pendingIntent)
			.setAutoCancel(true)
			.build()

		notificationManager.notify(NOTIFICATION_ID, notification)
	}
}
