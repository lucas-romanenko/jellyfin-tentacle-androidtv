package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.data.repository.SectionFetch
import org.jellyfin.androidtv.data.repository.SectionSource

/** What an in-place home refresh does with one row's fresh answer. */
enum class RowRefreshAction {
	/** Show the new items. */
	REPLACE,

	/** Keep what is on screen. */
	KEEP,

	/** The row is really gone or empty: rebuild the home so it leaves (and comes back later). */
	DROP,
}

object TentacleRowRefresh {
	/** Empty answers in a row before a row is believed empty (one may be a playlist mid-rebuild). */
	const val EMPTY_ANSWERS_TO_BELIEVE = 2

	/**
	 * @param currentCount cards the row shows now
	 * @param limit the row's item limit from the plugin, 0 when unknown (older plugin)
	 * @param emptyAnswersInARow server answers of "no items" for this row, this one included
	 * @param sameShrunkAnswerAgain this answer lists exactly the items of the previous one, which
	 *   was kept as a shrink too small to believe: a playlist caught mid-rebuild answers
	 *   differently a refresh later, a playlist that really shrank answers the same (#54)
	 */
	fun decide(
		currentCount: Int,
		fetch: SectionFetch,
		limit: Int,
		emptyAnswersInARow: Int,
		sameShrunkAnswerAgain: Boolean = false,
	): RowRefreshAction = when {
		// A 4xx: the user can no longer see the playlist.
		fetch.source == SectionSource.GONE -> RowRefreshAction.DROP
		// The server did not answer: what is on screen is at least as fresh as the saved copy.
		fetch.source == SectionSource.SAVED_COPY -> RowRefreshAction.KEEP
		// An empty answer is usually a playlist the backend is clearing and re-filling; a row
		// emptied on purpose answers empty again, and then it goes (#54).
		fetch.items.isEmpty() ->
			if (emptyAnswersInARow >= EMPTY_ANSWERS_TO_BELIEVE) RowRefreshAction.DROP else RowRefreshAction.KEEP
		// As many items as the row's limit is a complete answer, however much it shrank: the
		// dashboard lowered the limit (20 → 5 used to keep the 20 old cards) (#54).
		limit > 0 && fetch.items.size >= limit -> RowRefreshAction.REPLACE
		// Less than half of what is shown is most likely a playlist caught mid-rebuild, unless
		// the same smaller answer came twice: then the playlist really shrank (20 → 8 below a
		// limit of 20 used to keep the 20 old cards for ever) (#54).
		currentCount > 0 && fetch.items.size * 2 < currentCount ->
			if (sameShrunkAnswerAgain) RowRefreshAction.REPLACE else RowRefreshAction.KEEP
		else -> RowRefreshAction.REPLACE
	}
}
