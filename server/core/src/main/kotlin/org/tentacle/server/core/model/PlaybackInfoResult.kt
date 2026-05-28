package org.tentacle.server.core.model

data class PlaybackInfoResult(
    val mediaSources: List<ServerMediaSource>,
    val playSessionId: String?,
    val errorCode: PlaybackErrorCode?,
)
