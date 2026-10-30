package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import java.time.Duration
import java.time.LocalDateTime

/*
 * A recording Jellyfin is still writing, played from its start (#239).
 *
 * While it records, Jellyfin swaps the item's own media source for one of its own (another id,
 * File, infinite, no runtime), offered only as HLS: `live.m3u8`, an EVENT playlist that ffmpeg
 * remuxes from 0:00 of the growing .ts (stream copy, as fast as the disk reads) and keeps
 * appending to until the recording stops. So the player can seek anywhere in the part already
 * remuxed, and the stream doesn't end while it records. It is a second reader of the file only:
 * no new provider connection.
 *
 * Three things differ from a finished recording:
 * - PlaybackInfo must not name the item's own media source: that id isn't offered while it
 *   records, and Jellyfin answered NoCompatibleStream (what #229 saw).
 * - The request starts at 0 and the player seeks itself; a start position would make ffmpeg skip
 *   the part before it, which could then not be seeked back to.
 * - Jellyfin has no runtime for the item, and treats any position it is told on an item without
 *   a runtime as "watched to the end": the first progress report marked the recording watched and
 *   set its position to 0 (checked on 10.11.8). So the reports leave the position out, the stop
 *   is reported as failed (Jellyfin then leaves the user data alone), and the position is saved
 *   on its own ([growingStopPosition]).
 */

/** Jellyfin's status of a recording being written ([BaseItemDto.status]). */
private const val STATUS_IN_PROGRESS = "InProgress"

/** How far from the end of what's written a seek may land, so the player has segments to play. */
const val GROWING_EDGE_MARGIN_MS = 10_000L

/** How long a resume (or Watch live) waits for the server's remux to reach the position. */
const val GROWING_RESUME_WAIT_MS = 45_000L

/**
 * The start position that asks for the live end of a recording still recording (Watch live,
 * Lucas 30 Oct: one player, cable-box style). No recording is 24 days long.
 */
const val GROWING_LIVE_EDGE_MS: Long = 2_147_483_647L

fun isLiveEdgeStart(positionMs: Long): Boolean = positionMs >= GROWING_LIVE_EDGE_MS

/**
 * Whether the server's remux has caught up with the recording: what's written grew no faster
 * than 2.5x real time over [overMs]. While it catches up with a long recording it grows minutes
 * per second; once caught up, a segment (3 s) every 3 s.
 */
fun growingCaughtUp(grewMs: Long, overMs: Long): Boolean = overMs > 0 && grewMs * 2 < overMs * 5

/**
 * How long the recording should be by now, from its start ([BaseItemDto.startDate], the
 * programme's start: a rule's pre-padding only makes it longer), or 0 when unknown.
 */
fun growingExpectedMs(start: LocalDateTime?, now: LocalDateTime): Long =
	start?.let { Duration.between(it, now).toMillis() }?.takeIf { it in 1..86_400_000L } ?: 0L

/**
 * Whether Watch live can go to the live end now: what's written is as long as the recording
 * should be (less a minute), or its last growth ([grewMs] over [overMs], between two playlist
 * updates; negative before the second) was about real time, or it has waited
 * [GROWING_RESUME_WAIT_MS]. The first playlist the player gets on a long recording holds only
 * what the server remuxed in its first second (2:25 of a 35 min recording on 30 Oct).
 */
fun growingAtLiveEnd(writtenMs: Long, expectedMs: Long, grewMs: Long, overMs: Long, waitedMs: Long): Boolean =
	waitedMs >= GROWING_RESUME_WAIT_MS ||
		(writtenMs > 0 && expectedMs > 0 && writtenMs >= expectedMs - 60_000L) ||
		(grewMs >= 0 && growingCaughtUp(grewMs, overMs))

/** A recording that is still recording, by the item alone (before any PlaybackInfo). */
fun isRecordingInProgress(item: BaseItemDto?): Boolean =
	item != null && item.type == BaseItemKind.RECORDING && item.status == STATUS_IN_PROGRESS

/**
 * The source Jellyfin offers for a recording still being written: a local file, infinite, not a
 * tuner's live stream. A live channel is infinite too but always has a live stream id.
 */
fun isGrowingRecordingSource(itemType: BaseItemKind?, source: MediaSourceInfo?): Boolean =
	source != null && itemType != BaseItemKind.TV_CHANNEL &&
		source.isInfiniteStream && source.liveStreamId.isNullOrEmpty() && source.protocol == MediaProtocol.FILE

/** The item's media source id to ask PlaybackInfo for: none while it records (see above). */
fun playbackMediaSourceId(item: BaseItemDto?, sourceId: String?): String? =
	if (isRecordingInProgress(item)) null else sourceId

/**
 * Where a seek in a growing recording lands: never past what's written (less a margin), so
 * seeking or skipping to the end plays the latest part instead of ending the recording.
 * [writtenMs] 0 or less is unknown: the seek is left as asked.
 */
fun growingSeekTarget(requestedMs: Long, writtenMs: Long): Long = when {
	requestedMs <= 0 -> 0
	writtenMs <= 0 -> requestedMs
	else -> requestedMs.coerceAtMost((writtenMs - GROWING_EDGE_MARGIN_MS).coerceAtLeast(0))
}

/**
 * Whether a resume at [targetMs] can seek now: the server's remux has reached it, or it has had
 * [GROWING_RESUME_WAIT_MS] (then the seek goes as far as there is).
 */
fun growingResumeReady(targetMs: Long, writtenMs: Long, waitedMs: Long): Boolean =
	writtenMs >= targetMs + GROWING_EDGE_MARGIN_MS || waitedMs >= GROWING_RESUME_WAIT_MS

/**
 * The position (ms) to save for a growing recording when playback stops. Under a minute in is
 * "not started" (0), as Jellyfin's own resume threshold. The end of the stream after the
 * recording finished is reported as a normal stop instead, which marks it watched.
 */
fun growingStopPosition(positionMs: Long): Long = if (positionMs >= 60_000L) positionMs else 0L
