package org.tentacle.server.core.api

import org.tentacle.server.core.model.AuthResult
import org.tentacle.server.core.model.QuickConnectInfo
import org.tentacle.server.core.model.ServerUser

interface ServerAuthApi {
    suspend fun authenticateByName(username: String, password: String): AuthResult
    suspend fun getCurrentUser(): ServerUser
    suspend fun getPublicUsers(): List<ServerUser>
    suspend fun logout()

    // QuickConnect — implementations for Emby may throw UnsupportedOperationException
    suspend fun supportsQuickConnect(): Boolean
    suspend fun initiateQuickConnect(): QuickConnectInfo?
    suspend fun checkQuickConnectStatus(secret: String): Boolean
    suspend fun authenticateWithQuickConnect(secret: String): AuthResult
}
