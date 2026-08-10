package org.jellyfin.androidtv.ui.browsing

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.SessionRepositoryState
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.service.UpdateCheckerService
import org.jellyfin.androidtv.data.syncplay.SyncPlayManager
import org.jellyfin.androidtv.databinding.ActivityMainBinding
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.ThemeMusicPlayer
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.util.applyTheme
import org.jellyfin.androidtv.util.isMediaSessionKeyEvent
import org.jellyfin.playback.core.PlaybackManager
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber

class MainActivity : FragmentActivity() {
	companion object {
		// Session-scoped so the update prompt shows once per app launch, not on every
		// activity recreation
		private var updateDialogShown = false

		// Update waiting for the "install unknown apps" grant — resumed in onResume once
		// the user returns from the settings screen
		private var pendingUpdateInfo: UpdateCheckerService.UpdateInfo? = null
	}

	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	private val workManager by inject<WorkManager>()
	private val updateCheckerService by inject<UpdateCheckerService>()
	private val userPreferences by inject<UserPreferences>()
	private val themeMusicPlayer by inject<ThemeMusicPlayer>()
	private val syncPlayManager by inject<SyncPlayManager>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private val playbackManager by inject<PlaybackManager>()

	private lateinit var binding: ActivityMainBinding
	private val showExitDialog = mutableStateOf(false)

	private val backPressedCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			if (navigationRepository.canGoBack) {
				navigationRepository.goBack()
			} else {
				// User is on home screen — focus navbar first, exit on second press
				val navbarView = findViewById<View>(R.id.toolbar)?.takeIf { it.isShown }
					?: findViewById<View>(R.id.sidebar)?.takeIf { it.isShown }

				if (navbarView != null && navbarView.findFocus() !== currentFocus) {
					navbarView.requestFocus()
				} else {
					showExitConfirmation()
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		// Inflate layout immediately so the window has content during activity
		// transition — prevents black flash while waiting for session validation.
		binding = ActivityMainBinding.inflate(layoutInflater)
		binding.background.setContent { AppBackground() }
		setContentView(binding.root)

		// Wait for session restoration before validating authentication
		// This prevents race condition where activity recreates before session is restored
		lifecycleScope.launch {
			sessionRepository.state
				.filter { it == SessionRepositoryState.READY }
				.first()

			if (!validateAuthentication()) return@launch

			setupSyncPlayQueueLauncher()
			setupActivity(savedInstanceState)
		}
	}

	private fun setupActivity(savedInstanceState: Bundle?) {
		interactionTrackerViewModel.keepScreenOn.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { keepScreenOn ->
				if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}.launchIn(lifecycleScope)

		onBackPressedDispatcher.addCallback(this, backPressedCallback)
		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset(clearHistory = true)

		navigationRepository.currentAction
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach { action ->
				handleNavigationAction(action)
				// Always enable back callback to handle exit confirmation
				backPressedCallback.isEnabled = true
				interactionTrackerViewModel.notifyInteraction(canCancel = false, userInitiated = false)
			}.launchIn(lifecycleScope)

		// Layout already inflated + setContentView called in onCreate.
		// Replace the splash drawable window background with a plain color now that
		// the layout is rendered — prevents the logo from peeking through on other screens.
		window.setBackgroundDrawableResource(R.color.not_quite_black)

		// Wire up the remaining Compose views.
		binding.settings.setContent { MainActivitySettings() }
		binding.screensaver.setContent { InAppScreensaver() }
		binding.exitDialog.setContent {
			if (showExitDialog.value) {
				ExitConfirmationDialog(
					onConfirm = { finish() },
					onDismiss = { showExitDialog.value = false },
				)
			}
		}

		// Check for updates on app launch (libre builds only)
		if (org.jellyfin.androidtv.BuildConfig.ENABLE_OTA_UPDATES) {
			checkForUpdatesOnLaunch()
		}
	}
	
	private fun setupSyncPlayQueueLauncher() {
		// Set up callback to handle queue loading when no active PlaybackController exists
		syncPlayManager.queueLaunchCallback = { itemIds, startIndex, startPositionTicks ->
			lifecycleScope.launch {
				val queueResult = org.jellyfin.androidtv.data.syncplay.SyncPlayQueueHelper.fetchQueue(
					itemIds = itemIds,
					startIndex = startIndex,
					startPositionTicks = startPositionTicks,
				)
				
				if (queueResult != null) {
					playbackLauncher.launch(
						context = this@MainActivity,
						items = queueResult.items,
						position = queueResult.startPositionMs.toInt(),
						itemsPosition = queueResult.startIndex
					)
				}
			}
		}
	}

	override fun onResume() {
		super.onResume()

		// Skip auth check while session is still restoring — onCreate handles it once READY.
		// Prevents false bounce to StartupActivity when returning from external player after process death.
		if (sessionRepository.state.value != SessionRepositoryState.READY) return

		if (!validateAuthentication()) return

		applyTheme()

		interactionTrackerViewModel.activityPaused = false

		// Resume a pending update install after the user returns from granting the
		// "install unknown apps" permission
		pendingUpdateInfo?.let { updateInfo ->
			if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
				pendingUpdateInfo = null
				startUpdateDownload(updateInfo)
			}
		}
	}

	private fun validateAuthentication(): Boolean {
		if (sessionRepository.currentSession.value == null || userRepository.currentUser.value == null) {
			Timber.w("Activity ${this::class.qualifiedName} started without a session, bouncing to StartupActivity")
			startActivity(Intent(this, StartupActivity::class.java))
			finish()
			return false
		}

		return true
	}

	private fun checkForUpdatesOnLaunch() {
		// Check if update notifications are enabled
		if (!userPreferences[UserPreferences.updateNotificationsEnabled]) {
			Timber.d("Update notifications are disabled")
			return
		}

		// Only prompt once per app session
		if (updateDialogShown) return

		lifecycleScope.launch(Dispatchers.IO) {
			try {
				val result = updateCheckerService.checkForUpdate()
				result.onSuccess { updateInfo ->
					if (updateInfo != null && updateInfo.isNewer) {
						Timber.i("Update available: ${updateInfo.version}")
						launch(Dispatchers.Main) {
							if (!isFinishing && !updateDialogShown) {
								updateDialogShown = true
								showUpdateDialog(updateInfo)
							}
						}
					} else {
						Timber.d("No updates available")
					}
				}.onFailure { error ->
					Timber.e(error, "Failed to check for updates")
				}
			} catch (e: Exception) {
				Timber.e(e, "Error checking for updates on launch")
			}
		}
	}

	private fun showUpdateDialog(updateInfo: UpdateCheckerService.UpdateInfo) {
		android.app.AlertDialog.Builder(this)
			.setTitle(getString(R.string.update_dialog_title, updateInfo.version))
			.setMessage(updateInfo.releaseNotes.take(1500))
			.setPositiveButton(R.string.update_dialog_update_now) { dialog, _ ->
				dialog.dismiss()
				startUpdateDownload(updateInfo)
			}
			.setNegativeButton(R.string.update_dialog_later) { dialog, _ -> dialog.dismiss() }
			.show()
	}

	private fun startUpdateDownload(updateInfo: UpdateCheckerService.UpdateInfo) {
		// Sideloaded apps need the one-time "install unknown apps" grant before the system
		// installer will accept our APK. Send the user straight to the right settings screen.
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
			Toast.makeText(this, R.string.update_grant_install_permission, Toast.LENGTH_LONG).show()
			pendingUpdateInfo = updateInfo
			startActivity(
				Intent(
					android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
					android.net.Uri.parse("package:$packageName")
				)
			)
			return
		}

		val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
			isIndeterminate = false
			max = 100
		}
		val container = android.widget.FrameLayout(this).apply {
			val padding = (24 * resources.displayMetrics.density).toInt()
			setPadding(padding, padding, padding, padding)
			addView(progressBar)
		}
		val progressDialog = android.app.AlertDialog.Builder(this)
			.setTitle(getString(R.string.update_downloading, updateInfo.version))
			.setView(container)
			.setCancelable(false)
			.show()

		lifecycleScope.launch(Dispatchers.IO) {
			val result = updateCheckerService.downloadUpdate(updateInfo.downloadUrl) { progress ->
				progressBar.progress = progress
			}
			launch(Dispatchers.Main) {
				progressDialog.dismiss()
				result.onSuccess { apkUri ->
					updateCheckerService.installUpdate(apkUri)
				}.onFailure { error ->
					Timber.e(error, "Failed to download update")
					Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
				}
			}
		}
	}

	override fun onPause() {
		super.onPause()

		interactionTrackerViewModel.activityPaused = true
	}

	override fun onStop() {
		super.onStop()

		// Stop theme music when app goes to background
		themeMusicPlayer.stop()

		workManager.enqueue(OneTimeWorkRequestBuilder<LeanbackChannelWorker>().build())

		// Only destroy session if app is finishing, not just temporarily stopping
		if (isFinishing) {
			// Release the player backend (ExoPlayer decoders, threads, surfaces) on real teardown.
			// Safe to release the singleton here because the process is exiting.
			playbackManager.release()
			lifecycleScope.launch(Dispatchers.IO) {
				Timber.i("MainActivity finishing - destroying session")
				sessionRepository.restoreSession(destroyOnly = true)
			}
		} else {
			Timber.d("MainActivity stopped (not finishing) - preserving session")
		}
	}

	private fun handleNavigationAction(action: NavigationAction) {

		when (action) {
			is NavigationAction.NavigateFragment -> binding.contentView.navigate(action)
			NavigationAction.GoBack -> binding.contentView.goBack()
			NavigationAction.PopToFirst -> binding.contentView.popToFirst()

			NavigationAction.Nothing -> Unit
		}
	}

	// Forward key events to fragments
	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		// Ignore the key event that closes the screensaver
		if (interactionTrackerViewModel.visible.value) {
			interactionTrackerViewModel.notifyInteraction(canCancel = event?.action == KeyEvent.ACTION_UP, userInitiated = true)
			return true
		}

		return supportFragmentManager.fragments
			.any { it.onKeyEvent(keyCode, event) }
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onUserInteraction() {
		super.onUserInteraction()

		interactionTrackerViewModel.notifyInteraction(false, userInitiated = true)
	}

	@Suppress("RestrictedApi") // False positive
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		// Ignore the key event that closes the screensaver
		if (!event.isMediaSessionKeyEvent() && interactionTrackerViewModel.visible.value) {
			interactionTrackerViewModel.notifyInteraction(canCancel = event.action == KeyEvent.ACTION_UP, userInitiated = true)
			return true
		}

		@Suppress("RestrictedApi") // False positive
		return super.dispatchKeyEvent(event)
	}

	@Suppress("RestrictedApi") // False positive
	override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
		// Ignore the key event that closes the screensaver
		if (!event.isMediaSessionKeyEvent() && interactionTrackerViewModel.visible.value) {
			interactionTrackerViewModel.notifyInteraction(canCancel = event.action == KeyEvent.ACTION_UP, userInitiated = true)
			return true
		}

		@Suppress("RestrictedApi") // False positive
		return super.dispatchKeyShortcutEvent(event)
	}

	override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
		// Ignore the touch event that closes the screensaver
		if (interactionTrackerViewModel.visible.value) {
			interactionTrackerViewModel.notifyInteraction(canCancel = true, userInitiated = true)
			return true
		}

		return super.dispatchTouchEvent(ev)
	}

	private fun showExitConfirmation() {
		if (!userPreferences[UserPreferences.confirmExit]) {
			finish()
			return
		}

		showExitDialog.value = true
	}

	override fun onDestroy() {
		showExitDialog.value = false
		super.onDestroy()
	}

	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)

		// Check if this is just an input device change (keyboard/navigation)
		// These changes don't require theme reapplication and can cause visual glitches during playback
		val isInputDeviceChange = newConfig.keyboard != resources.configuration.keyboard ||
			newConfig.keyboardHidden != resources.configuration.keyboardHidden ||
			newConfig.navigation != resources.configuration.navigation

		if (isInputDeviceChange) {
			Timber.d("Input device configuration changed - preserving activity and playback state without theme reapplication")
			// Don't call applyTheme() for input device changes to avoid visual glitches during playback
		} else {
			Timber.d("Configuration changed - applying theme")
			applyTheme()
		}
	}
}
