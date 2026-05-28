package org.tentacle.server.core.feature

enum class ServerFeature {
    QUICK_CONNECT,
    SYNC_PLAY,
    WATCH_PARTY,
    MEDIA_SEGMENTS,
    TRICKPLAY,
    BIF_TRICKPLAY,
    LYRICS,
    CLIENT_LOG,
    EMBY_CONNECT,
}

interface ServerFeatureSupport {
    val supportedFeatures: Set<ServerFeature>
    fun isSupported(feature: ServerFeature): Boolean = feature in supportedFeatures
}
