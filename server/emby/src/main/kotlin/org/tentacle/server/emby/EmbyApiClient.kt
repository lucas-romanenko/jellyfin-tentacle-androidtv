package org.tentacle.server.emby

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.statement.HttpResponse
import org.emby.client.api.DisplayPreferencesServiceApi
import org.emby.client.api.InstantMixServiceApi
import org.emby.client.api.ItemsServiceApi
import org.emby.client.api.LibraryServiceApi
import org.emby.client.api.LiveTvServiceApi
import org.emby.client.api.MediaInfoServiceApi
import org.emby.client.api.PlaystateServiceApi
import org.emby.client.api.SessionsServiceApi
import org.emby.client.api.TvShowsServiceApi
import org.emby.client.api.UserLibraryServiceApi
import org.emby.client.api.UserServiceApi
import org.emby.client.api.UserViewsServiceApi
import org.emby.client.model.AuthenticateUserByName

data class EmbyUserInfo(
    val id: String,
    val name: String?,
    val serverId: String?,
    val primaryImageTag: String?,
    val hasPassword: Boolean?,
    val hasConfiguredPassword: Boolean?,
)

data class EmbyAuthResult(
    val accessToken: String?,
    val user: EmbyUserInfo?,
    val serverId: String?,
)

class EmbyApiClient(
    private val appVersion: String,
    private val clientName: String,
    val deviceId: String,
    private val deviceName: String,
) {
    var baseUrl: String = ""
        private set
    var accessToken: String? = null
        private set
    var userId: String? = null
        private set

    var userService: UserServiceApi? = null
        private set
    var sessionsService: SessionsServiceApi? = null
        private set
    var itemsService: ItemsServiceApi? = null
        private set
    var userLibraryService: UserLibraryServiceApi? = null
        private set
    var tvShowsService: TvShowsServiceApi? = null
        private set
    var libraryService: LibraryServiceApi? = null
        private set
    var playstateService: PlaystateServiceApi? = null
        private set
    var userViewsService: UserViewsServiceApi? = null
        private set
    var liveTvService: LiveTvServiceApi? = null
        private set
    var instantMixService: InstantMixServiceApi? = null
        private set
    var displayPreferencesService: DisplayPreferencesServiceApi? = null
        private set
    var mediaInfoService: MediaInfoServiceApi? = null
        private set

    /**
     * Single shared Ktor engine for all generated service clients. The generated [org.emby.client.api]
     * services each build their own [io.ktor.client.HttpClient] but accept a shared
     * [HttpClientEngine], so passing one engine reuses a single connection/thread pool across all
     * ~12 services. The engine is recreated on every (re)configure and closed when the client is
     * reset/reconfigured so engine threads and connections aren't leaked on session switches.
     */
    private var engine: HttpClientEngine? = null

    /**
     * Translates non-2xx responses into a typed [EmbyApiException] instead of letting callers hit
     * opaque serialization failures when they try to parse an error body as the expected model.
     */
    private val clientConfig: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
        HttpResponseValidator {
            validateResponse { response: HttpResponse ->
                val status = response.status.value
                if (status !in 200..299) {
                    throw EmbyApiException.fromStatus(status, response.call.request.url.encodedPath)
                }
            }
        }
    }

    private fun <T : org.emby.client.infrastructure.ApiClient> T.withApiKey(): T = apply {
        accessToken?.let { setApiKey(it) }
    }

    fun configure(baseUrl: String, accessToken: String?, userId: String?) {
        this.baseUrl = baseUrl
        this.accessToken = accessToken
        this.userId = userId

        // Release the previous engine (and its pools) before discarding the old services.
        engine?.close()
        engine = null

        if (baseUrl.isEmpty()) {
            userService = null
            sessionsService = null
            itemsService = null
            userLibraryService = null
            tvShowsService = null
            libraryService = null
            playstateService = null
            userViewsService = null
            liveTvService = null
            instantMixService = null
            displayPreferencesService = null
            mediaInfoService = null
            return
        }

        val sharedEngine = OkHttp.create()
        engine = sharedEngine

        userService = UserServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        sessionsService = SessionsServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        itemsService = ItemsServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        userLibraryService = UserLibraryServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        tvShowsService = TvShowsServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        libraryService = LibraryServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        playstateService = PlaystateServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        userViewsService = UserViewsServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        liveTvService = LiveTvServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        instantMixService = InstantMixServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        displayPreferencesService = DisplayPreferencesServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
        mediaInfoService = MediaInfoServiceApi(baseUrl, sharedEngine, clientConfig).withApiKey()
    }

    fun reset() = configure("", null, null)

    fun buildAuthHeader(token: String? = null): String = buildString {
        append("Emby Client=\"$clientName\"")
        append(", Device=\"$deviceName\"")
        append(", DeviceId=\"$deviceId\"")
        append(", Version=\"$appVersion\"")
        val t = token ?: accessToken
        if (t != null) append(", Token=\"$t\"")
    }

    suspend fun validateCurrentUser(): EmbyUserInfo {
        val id = userId ?: error("EmbyApiClient: userId not configured")
        val dto = userService!!.getUsersById(id).body()
        return EmbyUserInfo(
            id = dto.id ?: id,
            name = dto.name,
            serverId = dto.serverId,
            primaryImageTag = dto.primaryImageTag,
            hasPassword = dto.hasPassword,
            hasConfiguredPassword = dto.hasConfiguredPassword,
        )
    }

    suspend fun validateToken(): Boolean {
        if (!isConfigured) return false
        return try {
            validateCurrentUser()
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun authenticateByName(username: String, password: String): EmbyAuthResult {
        val body = AuthenticateUserByName(username = username, pw = password)
        // Reuse the shared-engine service when configured; only allocate a transient client
        // (e.g. pre-configure login probe) when no service exists yet.
        val service = userService ?: UserServiceApi(baseUrl, engine, clientConfig)
        val result = service.postUsersAuthenticatebyname(buildAuthHeader(), body).body()
        val userDto = result.user
        return EmbyAuthResult(
            accessToken = result.accessToken,
            serverId = result.serverId,
            user = userDto?.let {
                EmbyUserInfo(
                    id = it.id ?: "",
                    name = it.name,
                    serverId = it.serverId,
                    primaryImageTag = it.primaryImageTag,
                    hasPassword = it.hasPassword,
                    hasConfiguredPassword = it.hasConfiguredPassword,
                )
            },
        )
    }

    suspend fun postCapabilities(
        playableMediaTypes: String,
        supportedCommands: String,
        supportsMediaControl: Boolean,
    ) {
        sessionsService?.postSessionsCapabilities(
            id = "",
            playableMediaTypes = playableMediaTypes,
            supportedCommands = supportedCommands,
            supportsMediaControl = supportsMediaControl,
            supportsSync = false,
        )
    }

    suspend fun logout() = runCatching { sessionsService?.postSessionsLogout() }

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && accessToken != null
}
