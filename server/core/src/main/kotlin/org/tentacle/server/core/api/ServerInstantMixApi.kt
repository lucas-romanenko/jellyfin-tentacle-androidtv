package org.tentacle.server.core.api

import org.tentacle.server.core.model.ItemsResult

interface ServerInstantMixApi {
    suspend fun getInstantMix(itemId: String, userId: String? = null, limit: Int? = null): ItemsResult
}
