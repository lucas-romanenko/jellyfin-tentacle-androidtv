package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.eventhandling.StatusChanges
import org.jellyfin.androidtv.data.repository.GuideBanner
import org.jellyfin.androidtv.data.repository.GuideBannerTone
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.nextGuideBanner
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

/**
 * The line under the guide's header saying what is wrong with Live TV, in the server's words
 * (Tentacle's /TentacleDiscover/FamilyStatus): "IPTV Bears is down right now … comes back by
 * itself", and "IPTV Bears is back" for a while after. Gone when there is nothing to say.
 *
 * Read when the guide comes to the front, every minute while it stays there, and at once when
 * Tentacle pushes "status" ([StatusChanges]). Nothing runs while the guide isn't in front. The
 * read is in the background (3 s at most) and never holds the guide's own loading; a read with
 * no answer keeps the line as it was.
 */
class GuideStatusBanner(private val view: TextView) : KoinComponent {
	private val tentacleRepository by inject<TentacleRepository>()
	private var shown: GuideBanner? = null

	fun start(owner: LifecycleOwner) {
		owner.lifecycleScope.launch {
			owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				val reads = Channel<Unit>(Channel.CONFLATED)
				reads.trySend(Unit)
				launch { StatusChanges.changes.collect { reads.trySend(Unit) } }
				launch {
					while (isActive) {
						delay(REREAD)
						reads.trySend(Unit)
					}
				}
				for (read in reads) show(nextGuideBanner(shown, tentacleRepository.getFamilyStatus()))
			}
		}
	}

	private fun show(banner: GuideBanner?) {
		if (banner == shown) return
		shown = banner
		if (banner == null) {
			view.visibility = View.GONE
			return
		}
		val (background, textColor) = when (banner.tone) {
			GuideBannerTone.WARNING -> R.drawable.guide_status_warning_bg to R.color.guide_status_warning_text
			GuideBannerTone.BACK -> R.drawable.guide_status_back_bg to R.color.guide_status_back_text
			GuideBannerTone.NEUTRAL -> R.drawable.guide_status_neutral_bg to R.color.guide_text_muted
		}
		view.setBackgroundResource(background)
		view.setTextColor(ContextCompat.getColor(view.context, textColor))
		view.text = banner.text
		view.visibility = View.VISIBLE
	}

	private companion object {
		val REREAD = 60.seconds
	}
}
