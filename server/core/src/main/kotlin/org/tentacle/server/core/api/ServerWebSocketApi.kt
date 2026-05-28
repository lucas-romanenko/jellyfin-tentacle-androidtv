package org.tentacle.server.core.api

import kotlinx.coroutines.flow.Flow
import org.tentacle.server.core.model.ServerWebSocketMessage

interface ServerWebSocketApi {
    suspend fun connect()
    suspend fun disconnect()
    val messages: Flow<ServerWebSocketMessage>
}
