package org.tentacle.server.core.model

import java.time.Instant

data class LiveTvGuideInfo(
    val startDate: Instant?,
    val endDate: Instant?,
)
