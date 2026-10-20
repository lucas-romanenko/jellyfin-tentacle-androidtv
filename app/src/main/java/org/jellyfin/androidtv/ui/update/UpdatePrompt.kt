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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.SelfUpdateMode
import org.jellyfin.androidtv.data.service.UpdateCheckResult
import org.jellyfin.androidtv.data.service.UpdateCheckerService
import org.jellyfin.androidtv.data.service.UpdateInfo
import org.jellyfin.androidtv.data.service.isAutoCheckDue
import org.jellyfin.androidtv.data.service.isUpdateSnoozed
import org.jellyfin.androidtv.preference.UserPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * The update dialog and install flow, shared by the automatic check (MainActivity), the manual
 * "Check for updates" in Settings, About, and the notification. Two clicks for the user:
 * "Update now", then the system installer's "Install".
 */
object UpdatePrompt : KoinComponent {
	private val updateCheckerService by inject<UpdateCheckerService>()
	private val userPreferences by inject<UserPreferences>()

	/** When the automatic check last ran in this process; null = not yet. */
	private var lastAutoCheckMs: Long? = null

	/** The update dialog is on screen. */
	private var dialogShowing = false

	/** A download runs. */
	private var downloading = false

	/** Don't open a second dialog while one is up, a download runs or the grant is awaited. */
	private val busy get() = dialogShowing || downloading || pendingInstall != null

	/** Set by the notification's tap; MainActivity opens the dialog once it is up. */
	private val AUTO_CHECK_DELAY = 20.seconds

	@Volatile
	var openRequested = false

	/** An update waiting for the "install unknown apps" grant, resumed when the activity comes back. */
	private var pendingInstall: UpdateInfo? = null

	/** The automatic check: on launch, and on coming back after 12 hours. Quiet unless there is a new version. */
	fun autoCheck(activity: FragmentActivity) {
		if (updateCheckerService.mode != SelfUpdateMode.ENABLED) return
		if (openRequested) {
			openRequested = false
			checkNow(activity, quiet = false)
			return
		}
		if (!userPreferences[UserPreferences.updateNotificationsEnabled]) return
		val now = System.currentTimeMillis()
		if (!isAutoCheckDue(lastAutoCheckMs, now)) return
		lastAutoCheckMs = now
		activity.lifecycleScope.launch {
			// After Home has loaded, not during it: the check asks GitHub and parses its releases.
			delay(AUTO_CHECK_DELAY)
			val result = updateCheckerService.checkForUpdate()
			if (result !is UpdateCheckResult.Available) return@launch
			val snoozed = isUpdateSnoozed(
				version = result.info.version,
				snoozedVersion = userPreferences[UserPreferences.updateSnoozedVersion],
				snoozedAtMs = userPreferences[UserPreferences.updateSnoozedAt],
				nowMs = System.currentTimeMillis(),
			)
			if (snoozed) {
				Timber.i("Update ${result.info.version}: Later was pressed, not asking again yet")
				return@launch
			}
			showDialog(activity, result.info)
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
				is UpdateCheckResult.Available -> showDialog(activity, result.info)
				UpdateCheckResult.UpToDate ->
					if (!quiet) Toast.makeText(activity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
				is UpdateCheckResult.Failed ->
					if (!quiet) Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_LONG).show()
			}
		}
	}

	private fun showDialog(activity: FragmentActivity, info: UpdateInfo) {
		if (busy || !activity.canShowDialog) return
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
