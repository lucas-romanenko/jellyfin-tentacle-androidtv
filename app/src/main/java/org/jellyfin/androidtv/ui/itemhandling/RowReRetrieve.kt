package org.jellyfin.androidtv.ui.itemhandling

import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.model.DataRefreshService
import java.time.Instant

/** Whether a row must load again because something it shows changed after it last loaded. */
object RowReRetrieve {
	/**
	 * @param triggers what the row depends on (Continue Watching: movie and TV playback)
	 * @param lastFullRetrieve when the row last loaded; null while its first load is still running
	 */
	@JvmStatic
	fun isDue(triggers: Array<ChangeTriggerType>?, lastFullRetrieve: Instant?, refresh: DataRefreshService): Boolean {
		if (triggers == null || lastFullRetrieve == null) return false
		fun changedSince(time: Instant?) = time != null && lastFullRetrieve.isBefore(time)
		return triggers.any { trigger ->
			when (trigger) {
				ChangeTriggerType.LibraryUpdated -> changedSince(refresh.lastLibraryChange)
				ChangeTriggerType.MoviePlayback -> changedSince(refresh.lastMoviePlayback)
				ChangeTriggerType.TvPlayback -> changedSince(refresh.lastTvPlayback)
				ChangeTriggerType.FavoriteUpdate -> changedSince(refresh.lastFavoriteUpdate)
				ChangeTriggerType.MusicPlayback -> false
			}
		}
	}
}
