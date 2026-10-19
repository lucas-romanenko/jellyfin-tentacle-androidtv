package org.jellyfin.androidtv.data.repository

import org.jellyfin.androidtv.ui.playback.serverErrorDetail

/**
 * What came of deleting a title through Tentacle (`DELETE /TentacleDiscover/LibraryItem`).
 *
 * There is no fallback to a plain Jellyfin delete for a title Tentacle knows by its TMDB id, as
 * in the web client (tentacle-details.js): Tentacle refuses on purpose. After a failed Radarr/
 * Sonarr delete (502) it keeps its record for a retry, and a Jellyfin delete then removed the
 * file while Radarr still had the movie, so it was downloaded again.
 */
sealed interface DeleteOutcome {
	data object Deleted : DeleteOutcome

	/** Tentacle doesn't have the title (any more). */
	data object NotInLibrary : DeleteOutcome

	/** Tentacle said why it didn't delete (its own words). */
	data class Refused(val reason: String) : DeleteOutcome

	/** No usable answer: Tentacle unreachable, the plugin not configured, a server error. */
	data object Unreachable : DeleteOutcome

	/** A plain Jellyfin delete (an item Tentacle doesn't know) failed. */
	data object Failed : DeleteOutcome
}

/**
 * The plugin's answer to a delete. Only Tentacle's own explanation (a JSON `detail` on a 4xx or a
 * 502) is shown to the user; a 500 carries the plugin's exception text (which can name internal
 * hosts), and "Tentacle URL not configured" is plain text.
 */
fun deleteOutcome(code: Int, body: String?): DeleteOutcome = when {
	code in 200..299 -> DeleteOutcome.Deleted
	code == 404 -> DeleteOutcome.NotInLibrary
	code in 400..499 || code == 502 -> serverErrorDetail(body?.toByteArray())
		?.let { DeleteOutcome.Refused(it) } ?: DeleteOutcome.Unreachable
	else -> DeleteOutcome.Unreachable
}

/** The toast for [outcome]; [deleted] is the app's "<title> deleted" text. */
fun deleteOutcomeMessage(title: String, outcome: DeleteOutcome, deleted: String): String = when (outcome) {
	DeleteOutcome.Deleted -> deleted
	DeleteOutcome.NotInLibrary -> "$title is no longer in Tentacle's library"
	is DeleteOutcome.Refused -> "Couldn't delete $title: ${outcome.reason}"
	DeleteOutcome.Unreachable -> "Couldn't delete $title: can't reach Tentacle right now"
	DeleteOutcome.Failed -> "Couldn't delete $title"
}
