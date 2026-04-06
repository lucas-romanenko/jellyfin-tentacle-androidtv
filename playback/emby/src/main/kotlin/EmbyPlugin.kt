package org.moonfin.playback.emby

import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.sdk.model.api.DeviceProfile
import org.moonfin.playback.emby.mediastream.EmbyMediaStreamResolver
import org.moonfin.playback.emby.playsession.EmbyPlaySessionService
import org.moonfin.server.emby.EmbyApiClient

fun embyPlugin(
	api: EmbyApiClient,
	deviceProfileBuilder: () -> DeviceProfile,
) = playbackPlugin {
	provide(EmbyMediaStreamResolver(api, deviceProfileBuilder))
	provide(EmbyPlaySessionService(api))
}
