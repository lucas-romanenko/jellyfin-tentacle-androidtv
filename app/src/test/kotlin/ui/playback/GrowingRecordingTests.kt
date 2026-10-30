package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import java.util.UUID

/**
 * A recording still recording, played from its start (#239). What Jellyfin 10.11.8 answered for
 * one (throwaway server, 30 Oct): the item's own source id while it records gives
 * NoCompatibleStream; without it, a File source, infinite, no live stream id, HLS only.
 */
class GrowingRecordingTests : FunSpec({
	fun source(infinite: Boolean, liveStreamId: String? = null, protocol: MediaProtocol = MediaProtocol.FILE) = MediaSourceInfo(
		protocol = protocol, type = MediaSourceType.DEFAULT, isRemote = false, readAtNativeFramerate = false,
		ignoreDts = true, ignoreIndex = true, genPtsInput = false, supportsTranscoding = true,
		supportsDirectStream = false, supportsDirectPlay = false, isInfiniteStream = infinite,
		requiresOpening = false, requiresClosing = false, requiresLooping = false, supportsProbing = true,
		transcodingSubProtocol = MediaStreamProtocol.HLS, hasSegments = false, liveStreamId = liveStreamId,
	)
	fun recording(status: String?) = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.RECORDING, status = status)

	test("a recording is in progress by Jellyfin's status") {
		isRecordingInProgress(recording("InProgress")) shouldBe true
		isRecordingInProgress(recording("Completed")) shouldBe false
		isRecordingInProgress(recording(null)) shouldBe false
		isRecordingInProgress(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, status = "InProgress")) shouldBe false
		isRecordingInProgress(null) shouldBe false
	}

	test("PlaybackInfo names no media source while it records (its own id gives NoCompatibleStream)") {
		playbackMediaSourceId(recording("InProgress"), "abc") shouldBe null
		playbackMediaSourceId(recording("Completed"), "abc") shouldBe "abc"
		playbackMediaSourceId(BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE), "abc") shouldBe "abc"
	}

	test("the growing file's source: File, infinite, no live stream") {
		isGrowingRecordingSource(BaseItemKind.RECORDING, source(infinite = true)) shouldBe true
	}

	test("a live channel, a finished recording and a remote stream are not") {
		isGrowingRecordingSource(BaseItemKind.TV_CHANNEL, source(infinite = true, liveStreamId = "x")) shouldBe false
		isGrowingRecordingSource(BaseItemKind.RECORDING, source(infinite = true, liveStreamId = "x")) shouldBe false
		isGrowingRecordingSource(BaseItemKind.RECORDING, source(infinite = false)) shouldBe false
		isGrowingRecordingSource(BaseItemKind.MOVIE, source(infinite = true, protocol = MediaProtocol.HTTP)) shouldBe false
		isGrowingRecordingSource(BaseItemKind.RECORDING, null) shouldBe false
	}

	test("a seek stays within what's written, short of its end") {
		growingSeekTarget(60_000, 3_600_000) shouldBe 60_000
		growingSeekTarget(3_600_000, 3_600_000) shouldBe 3_590_000
		growingSeekTarget(9_000_000, 3_600_000) shouldBe 3_590_000
		growingSeekTarget(-5, 3_600_000) shouldBe 0
		growingSeekTarget(5_000, 4_000) shouldBe 0
		growingSeekTarget(60_000, 0) shouldBe 60_000
	}

	test("a resume waits for the server's remux to reach it, at most 45 s") {
		growingResumeReady(targetMs = 1_800_000, writtenMs = 600_000, waitedMs = 2_000) shouldBe false
		growingResumeReady(targetMs = 1_800_000, writtenMs = 1_810_000, waitedMs = 2_000) shouldBe true
		growingResumeReady(targetMs = 1_800_000, writtenMs = 600_000, waitedMs = 45_000) shouldBe true
	}

	test("Watch live asks for the live end with a position no recording reaches") {
		isLiveEdgeStart(GROWING_LIVE_EDGE_MS) shouldBe true
		isLiveEdgeStart(Int.MAX_VALUE.toLong()) shouldBe true
		isLiveEdgeStart(4 * 3_600_000L) shouldBe false
		isLiveEdgeStart(0) shouldBe false
		growingSeekTarget(GROWING_LIVE_EDGE_MS, 3_600_000) shouldBe 3_590_000
	}

	test("the remux has caught up once it grows at about real time") {
		growingCaughtUp(grewMs = 600_000, overMs = 5_000) shouldBe false
		growingCaughtUp(grewMs = 6_000, overMs = 5_000) shouldBe true
		growingCaughtUp(grewMs = 0, overMs = 5_000) shouldBe true
		growingCaughtUp(grewMs = 12_500, overMs = 5_000) shouldBe false
		growingCaughtUp(grewMs = 3_000, overMs = 0) shouldBe false
	}

	test("the position saved at a stop: under a minute is not started") {
		growingStopPosition(30_000) shouldBe 0
		growingStopPosition(0) shouldBe 0
		growingStopPosition(754_000) shouldBe 754_000
	}
})
