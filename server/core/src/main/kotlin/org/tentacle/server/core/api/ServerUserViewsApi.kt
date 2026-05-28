package org.tentacle.server.core.api

import org.tentacle.server.core.model.ServerItem

interface ServerUserViewsApi {
    suspend fun getUserViews(userId: String): List<ServerItem>
}
