package org.jellyfin.androidtv.data.eventhandling

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Something about recordings changed: Jellyfin set or cancelled a timer (TimerCreated,
 * TimerCancelled, SeriesTimer…; whoever did it: this TV, the web, Tentacle's teams and rules),
 * or Tentacle pushed "recordings" (a team or rule changed). Screens that show recordings (the
 * guide's record icons, Schedule, Recordings) read theirs again in place.
 */
object RecordingChanges {
	private val flow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
	val changes: SharedFlow<Unit> = flow.asSharedFlow()

	fun changed() {
		flow.tryEmit(Unit)
	}
}
