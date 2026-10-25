package org.jellyfin.androidtv.util.sdk

import org.jellyfin.androidtv.ui.browsing.v2.airStartFromPath
import org.jellyfin.androidtv.ui.browsing.v2.recordingAirStart
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * A finished Live TV recording is a Movie to Jellyfin, with the year it was recorded, so its card
 * said "Movie • 2026". Cards and meta lines show "Recording · Fri 23 Oct" for it instead: the air
 * day, as the Recordings screen writes it (#219). Finished recordings carry no channel.
 *
 * A recording is known by its path: Jellyfin's recordings folder (<data>/livetv/recordings), or,
 * for a recordings folder set elsewhere, the file name Jellyfin gives a recording
 * ("Title 2026_10_23_20_00_00.ts"). Every list the app shows cards from asks for Path.
 */

private const val RECORDING = "Recording"

private val recordingsFolder = Regex("""[/\\]livetv[/\\]recordings[/\\]""", RegexOption.IGNORE_CASE)

/** True for the path of a Live TV recording. */
fun isLiveTvRecordingPath(path: String?): Boolean {
	if (path.isNullOrEmpty()) return false
	if (recordingsFolder.containsMatchIn(path)) return true
	return path.endsWith(".ts", ignoreCase = true) && airStartFromPath(path) != null
}

/** True for a finished Live TV recording in the library (Jellyfin files them as Movies or Videos). */
fun isLiveTvRecording(type: BaseItemKind?, path: String?): Boolean =
	(type == BaseItemKind.MOVIE || type == BaseItemKind.VIDEO) && isLiveTvRecordingPath(path)

/** "Recording · Fri 23 Oct", or "Recording" when the day isn't known. */
fun recordingLabel(aired: LocalDate?, locale: Locale = Locale.getDefault()): String =
	aired?.let { "$RECORDING · ${DateTimeFormatter.ofPattern("EEE d MMM", locale).format(it)}" } ?: RECORDING

val BaseItemDto.isLiveTvRecording: Boolean
	get() = isLiveTvRecording(type, path)

/** "Recording · Fri 23 Oct" for a Live TV recording: the day it aired. */
val BaseItemDto.recordingLabel: String
	get() = recordingLabel(recordingAirStart(this)?.toLocalDate())

/** A Tentacle YouTube video or a Live TV recording: its card has [tentacleLabel] instead of the year. */
val BaseItemDto.hasTentacleLabel: Boolean
	get() = isTentacleYouTube || isLiveTvRecording

/** "YouTube · <channel>" or "Recording · Fri 23 Oct"; null for anything else (or a YouTube video without a channel). */
val BaseItemDto.tentacleLabel: String?
	get() = when {
		isTentacleYouTube -> youTubeSubtitle
		isLiveTvRecording -> recordingLabel
		else -> null
	}
