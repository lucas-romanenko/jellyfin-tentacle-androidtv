package org.jellyfin.androidtv.util

import org.jellyfin.androidtv.auth.model.Server
import org.tentacle.server.core.feature.ServerFeature
import org.tentacle.server.core.feature.ServerFeatureSupport
import org.tentacle.server.core.model.ServerType
import org.tentacle.server.emby.feature.EmbyFeatureSupport
import org.tentacle.server.jellyfin.feature.JellyfinFeatureSupport

fun ServerType.featureSupport(): ServerFeatureSupport = when (this) {
    ServerType.JELLYFIN -> JellyfinFeatureSupport
    ServerType.EMBY -> EmbyFeatureSupport
}

fun Server?.supportsFeature(feature: ServerFeature): Boolean =
    this?.serverType?.featureSupport()?.isSupported(feature) ?: true
