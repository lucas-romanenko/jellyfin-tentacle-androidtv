package org.tentacle.server.emby

import org.tentacle.server.core.api.MediaServerClient
import org.tentacle.server.core.api.ServerAuthApi
import org.tentacle.server.core.api.ServerDisplayPreferencesApi
import org.tentacle.server.core.api.ServerImageApi
import org.tentacle.server.core.api.ServerInstantMixApi
import org.tentacle.server.core.api.ServerItemsApi
import org.tentacle.server.core.api.ServerLiveTvApi
import org.tentacle.server.core.api.ServerPlaybackApi
import org.tentacle.server.core.api.ServerSessionApi
import org.tentacle.server.core.api.ServerSystemApi
import org.tentacle.server.core.api.ServerUserLibraryApi
import org.tentacle.server.core.api.ServerUserViewsApi
import org.tentacle.server.core.model.DeviceInfo
import org.tentacle.server.core.model.ServerType
import org.tentacle.server.emby.api.EmbyAuthApi
import org.tentacle.server.emby.api.EmbyDisplayPreferencesApi
import org.tentacle.server.emby.api.EmbyImageApi
import org.tentacle.server.emby.api.EmbyInstantMixApi
import org.tentacle.server.emby.api.EmbyItemsApi
import org.tentacle.server.emby.api.EmbyLiveTvApi
import org.tentacle.server.emby.api.EmbyPlaybackApi
import org.tentacle.server.emby.api.EmbySessionApi
import org.tentacle.server.emby.api.EmbySystemApi
import org.tentacle.server.emby.api.EmbyUserLibraryApi
import org.tentacle.server.emby.api.EmbyUserViewsApi

class EmbyMediaServerClient(
    private var deviceInfo: DeviceInfo,
) : MediaServerClient {

    override val serverType: ServerType = ServerType.EMBY

    private var apiClient: EmbyApiClient = createApiClient(deviceInfo)

    override val baseUrl: String? get() = apiClient.baseUrl.ifEmpty { null }
    override val accessToken: String? get() = apiClient.accessToken

    override val authApi: ServerAuthApi get() = EmbyAuthApi(apiClient)
    override val itemsApi: ServerItemsApi get() = EmbyItemsApi(apiClient)
    override val userLibraryApi: ServerUserLibraryApi get() = EmbyUserLibraryApi(apiClient)
    override val playbackApi: ServerPlaybackApi get() = EmbyPlaybackApi(apiClient)
    override val sessionApi: ServerSessionApi get() = EmbySessionApi(apiClient)
    override val imageApi: ServerImageApi get() = EmbyImageApi(apiClient)
    override val systemApi: ServerSystemApi get() = EmbySystemApi(apiClient)
    override val liveTvApi: ServerLiveTvApi get() = EmbyLiveTvApi(apiClient)
    override val instantMixApi: ServerInstantMixApi get() = EmbyInstantMixApi(apiClient)

    // These two wrappers maintain in-memory caches (user views / display prefs). They are kept as
    // stable instances so the caches actually persist between calls — building a fresh wrapper per
    // access (as a `get()` factory) would make the caches dead, never surviving a single call.
    // Recreated whenever the underlying apiClient is replaced (configure).
    private var _userViewsApi = EmbyUserViewsApi(apiClient)
    override val userViewsApi: ServerUserViewsApi get() = _userViewsApi

    private var _displayPreferencesApi = EmbyDisplayPreferencesApi(apiClient)
    override val displayPreferencesApi: ServerDisplayPreferencesApi get() = _displayPreferencesApi

    override fun configure(baseUrl: String, accessToken: String?, userId: String?, deviceInfo: DeviceInfo) {
        this.deviceInfo = deviceInfo
        apiClient = createApiClient(deviceInfo)
        apiClient.configure(baseUrl, accessToken, userId)
        // Rebuild the caching wrappers against the new apiClient (also clears stale cached data).
        _userViewsApi = EmbyUserViewsApi(apiClient)
        _displayPreferencesApi = EmbyDisplayPreferencesApi(apiClient)
    }

    override fun createForServer(baseUrl: String, accessToken: String?, deviceInfo: DeviceInfo): MediaServerClient {
        val client = EmbyMediaServerClient(deviceInfo)
        client.apiClient.configure(baseUrl, accessToken, null)
        return client
    }

    private fun createApiClient(info: DeviceInfo) = EmbyApiClient(
        appVersion = info.appVersion,
        clientName = info.appName,
        deviceId = info.id,
        deviceName = info.name,
    )
}
