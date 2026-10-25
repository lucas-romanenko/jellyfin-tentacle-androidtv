package org.jellyfin.androidtv.data.eventhandling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tentacle's plain-words status changed (it pushed "status": the Live TV provider went down,
 * got blocked, came back…). The guide's Live TV line reads /TentacleDiscover/FamilyStatus again
 * at once instead of waiting for its next minute.
 */
object StatusChanges {
	private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
	val changes: SharedFlow<Unit> = flow.asSharedFlow()

	fun changed() {
		flow.tryEmit(Unit)
	}
}
