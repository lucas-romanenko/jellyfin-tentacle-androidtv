package org.tentacle.server.core.model

data class ServerPerson(
    val id: String?,
    val name: String,
    val role: String?,
    val type: PersonType,
    val primaryImageTag: String?,
)
