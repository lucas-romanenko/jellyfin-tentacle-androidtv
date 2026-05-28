package org.tentacle.server.core.model

data class QuickConnectInfo(
    val secret: String,
    val code: String,
    val authenticated: Boolean = false,
)
