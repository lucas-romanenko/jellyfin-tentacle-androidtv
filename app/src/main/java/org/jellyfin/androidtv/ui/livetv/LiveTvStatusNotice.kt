package org.jellyfin.androidtv.ui.livetv

import android.os.SystemClock
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.liveTvNotice
import org.jellyfin.androidtv.data.repository.shouldShowNotice
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Live TV problems in plain words: the Tentacle server's Live TV message from
 * /TentacleDiscover/FamilyStatus ("Live TV is unavailable right now. Lucas knows and is on it.")
 * when Live TV fails. Without one (older server, no answer within 3 s) the app's own message
 * shows, as before. The guide itself shows it as a line under its header ([GuideStatusBanner]).
 */
object LiveTvStatusNotice : KoinComponent {
	private val tentacleRepository by inject<TentacleRepository>()

	private var lastText: String? = null
	private var lastShownMs = 0L

	/** Live TV failed: the server's Live TV message instead of [fallback]; [fallback] null = only the server's. */
	@JvmStatic
	fun onLiveTvError(fragment: Fragment, fallback: String?) = show(fragment, fallback)

	private fun show(fragment: Fragment, fallback: String?) {
		fragment.lifecycleScope.launch {
			val status = tentacleRepository.getFamilyStatus()
			val text = liveTvNotice(status, fallback) ?: return@launch
			val context = fragment.context ?: return@launch
			if (!fragment.isAdded) return@launch
			val now = SystemClock.elapsedRealtime()
			if (!shouldShowNotice(text, lastText, lastShownMs, now)) return@launch
			lastText = text
			lastShownMs = now
			Toast.makeText(context, text, Toast.LENGTH_LONG).show()
		}
	}
}
