package org.tentacle.server.core.api

import org.tentacle.server.core.model.PublicSystemInfo
import org.tentacle.server.core.model.SystemInfo

interface ServerSystemApi {
    suspend fun getPublicSystemInfo(): PublicSystemInfo
    suspend fun getSystemInfo(): SystemInfo
}
