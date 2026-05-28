package org.tentacle.server.core.api

import org.tentacle.server.core.model.ClientCapabilities
import org.tentacle.server.core.model.SessionInfo

interface ServerSessionApi {
    suspend fun postCapabilities(capabilities: ClientCapabilities)
    suspend fun getSessions(): List<SessionInfo>
}
