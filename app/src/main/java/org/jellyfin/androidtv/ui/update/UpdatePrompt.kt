package org.jellyfin.androidtv.ui.update

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.SelfUpdateMode
import org.jellyfin.androidtv.data.service.UpdateCheckResult
import org.jellyfin.androidtv.data.service.UpdateCheckerService
import org.jellyfin.androidtv.data.service.UpdateInfo
import org.jellyfin.androidtv.data.service.isHomeCheckDue
import org.jellyfin.androidtv.data.service.isUpdateSnoozed
import org.jellyfin.androidtv.preference.UserPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The update dialog and install flow, shared by the automatic check (Home), the manual
 * "Check for updates" in Settings, About, and the notification. Two clicks for the user:
 * "Update now", then the system installer's "Install".
 */
object UpdatePrompt : KoinComponent {
	private val updateCheckerService by inject<UpdateCheckerService>()
	private val userPreferences by inject<UserPreferences>()

	/** When Home's last check got no answer in this process; null = none. */
	private var lastFailedCheckMs: Long? = null

	/** Home's check runs (waiting for Home to settle, or asking GitHub). */
	private var homeCheckRunning = false

	/** The update dialog is on screen. */
	private var dialogShowing = false

	/** A download runs. */
	private var downloading = false

	/** Don't open a second dialog while one is up, a download runs or the grant is awaited. */
	private val busy get() = dialogShowing || downloading || pendingInstall != null

	/** How long Home must stay idle on screen (loaded, no screensaver) before the check asks GitHub. */
	private val HOME_SETTLE_DELAY = 3.seconds

	/** How long Home's check waits for Home to finish loading. */
	private val HOME_LOAD_TIMEOUT = 30.seconds

	/** Set by the notification's tap; MainActivity opens the dialog once it is up. */
	@Volatile
	var openRequested = false

	/** An update waiting for the "install unknown apps" grant, resumed when the activity comes back. */
	private var pendingInstall: UpdateInfo? = null

	/** The notification was selected: check and show the result now. */
	fun openIfRequested(activity: FragmentActivity) {
		if (!openRequested || updateCheckerService.mode != SelfUpdateMode.ENABLED) return
		openRequested = false
		checkNow(activity, quiet = false)
	}

	/**
	 * The automatic check, each time [home] is shown: opening the app, coming back to it, back
	 * from playback or another screen. It asks GitHub when the last check the person saw is 6
	 * hours old, or at once when the background check found a newer version ([isHomeCheckDue]).
	 * It waits until Home has loaded and [homeIdle] (no loading screen, no screensaver), and
	 * shows the dialog only while Home is still in front, so never over playback. Quiet unless
	 * there is a new version.
	 */
	fun onHomeShown(home: Fragment, homeIdle: () -> Boolean) {
		if (updateCheckerService.mode != SelfUpdateMode.ENABLED) return
		if (!userPreferences[UserPreferences.updateNotificationsEnabled]) return
		if (homeCheckRunning || busy) return
		val foundVersion = userPreferences[UserPreferences.updateFoundVersion]
		val due = isHomeCheckDue(
			installedVersion = updateCheckerService.installedVersion,
			lastSeenCheckMs = userPreferences[UserPreferences.updateSeenCheckAt],
			foundVersion = foundVersion,
			foundSnoozed = foundVersion.isNotBlank() && isSnoozed(foundVersion),
			lastFailedMs = lastFailedCheckMs,
			nowMs = System.currentTimeMillis(),
		)
		if (!due) return
		homeCheckRunning = true
		home.lifecycleScope.launch {
			try {
				// After Home has loaded, not during it: the check asks GitHub and parses its releases
				val settled = withTimeoutOrNull(HOME_LOAD_TIMEOUT) {
					while (!(home.isResumed && homeIdle())) delay(500)
					delay(HOME_SETTLE_DELAY)
				} != null
				// Left Home (playback, another screen, the background): next time Home shows
				if (!settled || !home.isResumed || !homeIdle()) return@launch

				val result = updateCheckerService.checkForUpdate()
				if (result is UpdateCheckResult.Failed) {
					lastFailedCheckMs = System.currentTimeMillis()
					return@launch
				}
				lastFailedCheckMs = null
				if (result is UpdateCheckResult.Available) {
					userPreferences[UserPreferences.updateFoundVersion] = result.info.version
					if (isSnoozed(result.info.version)) {
						Timber.i("Update ${result.info.version}: Later was pressed, not asking again yet")
					} else {
						val activity = home.activity
						if (activity == null || !home.isResumed || !homeIdle()) return@launch
						if (!showDialog(activity, result.info)) return@launch
					}
				}
				recordSeenCheck(result)
			} finally {
				homeCheckRunning = false
			}
		}
	}

	private fun isSnoozed(version: String) = isUpdateSnoozed(
		version = version,
		snoozedVersion = userPreferences[UserPreferences.updateSnoozedVersion],
		snoozedAtMs = userPreferences[UserPreferences.updateSnoozedAt],
		nowMs = System.currentTimeMillis(),
	)

	/** The person saw the answer (up to date, or the dialog): Home asks again in 6 hours. */
	private fun recordSeenCheck(result: UpdateCheckResult) {
		userPreferences[UserPreferences.updateSeenCheckAt] = System.currentTimeMillis()
		if (result !is UpdateCheckResult.Available || !isSnoozed(result.info.version)) {
			userPreferences[UserPreferences.updateFoundVersion] = ""
		}
	}

	/**
	 * Check now and say what came of it (the manual check, a notification tap). [quiet] keeps
	 * "you're on the latest version" and failures to the log. [onDone] runs when the check ends.
	 */
	fun checkNow(activity: FragmentActivity, quiet: Boolean = false, onDone: () -> Unit = {}) {
		when (updateCheckerService.mode) {
			SelfUpdateMode.DISABLED -> return onDone()
			SelfUpdateMode.DEBUG_BUILD -> {
				if (!quiet) Toast.makeText(activity, R.string.update_debug_build, Toast.LENGTH_LONG).show()
				return onDone()
			}
			SelfUpdateMode.ENABLED -> Unit
		}
		activity.lifecycleScope.launch {
			val result = updateCheckerService.checkForUpdate()
			onDone()
			when (result) {
				is UpdateCheckResult.Available -> if (showDialog(activity, result.info)) recordSeenCheck(result)
				UpdateCheckResult.UpToDate -> {
					recordSeenCheck(result)
					if (!quiet) Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_LONG).show()
				}
				is UpdateCheckResult.Failed ->
					if (!quiet) Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
			}
		}
	}

	/** Shows the dialog for [info]; false when it can't now (another one is up, the activity is going). */
	private fun showDialog(activity: FragmentActivity, info: UpdateInfo): Boolean {
		if (busy || !activity.canShowDialog) return false
		dialogShowing = true
		val notes = info.releaseNotes.ifBlank { activity.getString(R.string.update_dialog_no_notes) }
		AlertDialog.Builder(activity)
			.setTitle(activity.getString(R.string.update_dialog_title, info.version))
			.setMessage(notes.take(1500))
			.setPositiveButton(R.string.update_dialog_update_now) { dialog, _ ->
				dialog.dismiss()
				startDownload(activity, info)
			}
			.setNegativeButton(R.string.update_dialog_later) { dialog, _ ->
				userPreferences[UserPreferences.updateSnoozedVersion] = info.version
				userPreferences[UserPreferences.updateSnoozedAt] = System.currentTimeMillis()
				dialog.dismiss()
			}
			.setOnDismissListener { dialogShowing = false }
			.show()
			.also { dialog -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
		return true
	}

	private fun startDownload(activity: FragmentActivity, info: UpdateInfo) {
		// A sideloaded app needs the one-time "install unknown apps" grant before the system
		// installer accepts its download: open that screen, and carry on when the user is back.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
			pendingInstall = info
			Toast.makeText(activity, R.string.update_grant_install_permission, Toast.LENGTH_LONG).show()
			activity.lifecycle.addObserver(ResumePendingInstall(activity))
			val opened = runCatching {
				activity.startActivity(
					Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
				)
			}
			if (opened.isFailure) {
				Timber.w(opened.exceptionOrNull(), "No unknown-sources settings screen")
				pendingInstall = null
				Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
			}
			return
		}

		downloading = true
		val density = activity.resources.displayMetrics.density
		val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
			isIndeterminate = true
			max = 100
		}
		val percentText = TextView(activity).apply {
			gravity = Gravity.CENTER_HORIZONTAL
			text = activity.getString(R.string.update_download_percent, 0)
		}
		val container = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			val padding = (24 * density).toInt()
			setPadding(padding, padding, padding, padding)
			addView(progressBar)
			addView(percentText)
		}
		val progressDialog = AlertDialog.Builder(activity)
			.setTitle(activity.getString(R.string.update_downloading, info.version))
			.setView(container)
			.setCancelable(false)
			.show()

		activity.lifecycleScope.launch {
			val result = try {
				updateCheckerService.downloadUpdate(info.downloadUrl) { percent ->
					progressBar.isIndeterminate = false
					progressBar.progress = percent
					percentText.text = activity.getString(R.string.update_download_percent, percent)
				}
			} finally {
				downloading = false
				runCatching { progressDialog.dismiss() }
			}
			result.onSuccess { apkUri ->
				runCatching { updateCheckerService.installUpdate(apkUri) }.onFailure { error ->
					Timber.e(error, "Could not open the installer")
					Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
				}
			}.onFailure { error ->
				Timber.e(error, "Failed to download update")
				Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
			}
		}
	}

	/** Resumes the install once the user is back from the unknown-sources screen with the grant. */
	private class ResumePendingInstall(private val activity: FragmentActivity) : DefaultLifecycleObserver {
		private var leftOnce = false

		override fun onPause(owner: LifecycleOwner) {
			leftOnce = true
		}

		override fun onResume(owner: LifecycleOwner) {
			if (!leftOnce) return
			activity.lifecycle.removeObserver(this)
			val info = pendingInstall ?: return
			pendingInstall = null
			val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()
			if (granted) startDownload(activity, info)
		}

		override fun onDestroy(owner: LifecycleOwner) {
			activity.lifecycle.removeObserver(this)
			pendingInstall = null
		}
	}
}

/** The FragmentActivity behind a (Compose) context, if any. */
fun Context.findFragmentActivity(): FragmentActivity? {
	var context: Context? = this
	while (context != null) {
		if (context is FragmentActivity) return context
		context = (context as? ContextWrapper)?.baseContext
	}
	return null
}

/** True while the activity can show a dialog. */
internal val FragmentActivity.canShowDialog: Boolean
	get() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !isFinishing
