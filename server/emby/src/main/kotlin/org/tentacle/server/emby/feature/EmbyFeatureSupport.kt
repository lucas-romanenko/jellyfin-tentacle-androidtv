package org.tentacle.server.emby.feature

import org.tentacle.server.core.feature.ServerFeature
import org.tentacle.server.core.feature.ServerFeatureSupport

object EmbyFeatureSupport : ServerFeatureSupport {
    override val supportedFeatures: Set<ServerFeature> = setOf(
        ServerFeature.WATCH_PARTY,
        ServerFeature.BIF_TRICKPLAY,
        ServerFeature.EMBY_CONNECT,
    )
}
