@file:JvmName("RemoteSubtitlePolicy")

package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/** A source the server fetches from elsewhere (a provider's .strm VOD link) instead of its own disks. */
fun isRemoteSource(isRemote: Boolean?, protocol: MediaProtocol?): Boolean =
	isRemote == true || protocol == MediaProtocol.HTTP

fun isRemoteSource(source: MediaSourceInfo?): Boolean =
	source != null && isRemoteSource(source.isRemote, source.protocol)

/** How the server would deliver the subtitle stream [index] of [source]; null for none (-1) or unknown. */
fun subtitleDelivery(source: MediaSourceInfo?, index: Int?): SubtitleDeliveryMethod? {
	if (source == null || index == null || index < 0) return null
	return source.mediaStreams
		?.firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
		?.deliveryMethod
}

/**
 * Whether to start a remote source without the subtitle the server chose by itself (H1, audit
 * 2026-10-19). Drawing a subtitle onto the picture makes the server read the whole remote file
 * to extract it first, minutes before the first frame, so the start timed out. A subtitle the
 * user picked is kept: then the wait is expected.
 */
fun skipAutoBurnIn(userPicked: Boolean, remote: Boolean, delivery: SubtitleDeliveryMethod?): Boolean =
	!userPicked && remote && delivery == SubtitleDeliveryMethod.ENCODE

/** [skipAutoBurnIn] for the subtitle a PlaybackInfo answer selected ([requestedIndex], else its default). */
fun skipAutoBurnIn(userPicked: Boolean, source: MediaSourceInfo?, requestedIndex: Int?): Boolean =
	skipAutoBurnIn(userPicked, isRemoteSource(source), subtitleDelivery(source, requestedIndex ?: source?.defaultSubtitleStreamIndex))
