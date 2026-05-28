package org.tentacle.server.core.api

import org.tentacle.server.core.model.PlaybackInfoRequest
import org.tentacle.server.core.model.PlaybackInfoResult
import org.tentacle.server.core.model.PlaybackProgressReport
import org.tentacle.server.core.model.PlaybackStartReport
import org.tentacle.server.core.model.PlaybackStopReport
import org.tentacle.server.core.model.StreamParams

interface ServerPlaybackApi {
    suspend fun getPlaybackInfo(itemId: String, request: PlaybackInfoRequest): PlaybackInfoResult
    fun getVideoStreamUrl(itemId: String, params: StreamParams): String
    fun getAudioStreamUrl(itemId: String, params: StreamParams): String
    suspend fun reportPlaybackStart(info: PlaybackStartReport)
    suspend fun reportPlaybackProgress(info: PlaybackProgressReport)
    suspend fun reportPlaybackStopped(info: PlaybackStopReport)
}
