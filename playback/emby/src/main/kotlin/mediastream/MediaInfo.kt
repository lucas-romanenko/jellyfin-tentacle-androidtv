package org.tentacle.playback.emby.mediastream

import org.emby.client.model.MediaSourceInfo

data class MediaInfo(
	val playSessionId: String,
	val mediaSource: MediaSourceInfo,
)
