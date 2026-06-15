package org.tentacle.playback.emby

import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.sdk.model.api.DeviceProfile
import org.tentacle.playback.emby.mediastream.EmbyMediaStreamResolver
import org.tentacle.playback.emby.playsession.EmbyPlaySessionService
import org.tentacle.server.emby.EmbyApiClient

fun embyPlugin(
	api: EmbyApiClient,
	deviceProfileBuilder: () -> DeviceProfile,
	isActive: () -> Boolean = { true },
) = playbackPlugin {
	provide(EmbyMediaStreamResolver(api, deviceProfileBuilder))
	provide(EmbyPlaySessionService(api, isActive))
}
