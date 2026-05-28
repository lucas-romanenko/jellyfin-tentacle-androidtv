package org.tentacle.server.core.model

data class AuthResult(
    val accessToken: String,
    val user: ServerUser,
    val serverId: String?,
)
