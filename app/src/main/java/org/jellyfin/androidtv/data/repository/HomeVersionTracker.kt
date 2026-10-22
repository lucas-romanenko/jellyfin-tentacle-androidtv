package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** The home version a LibraryChanged message carries, and whether it is everyone's (a sync) or one user's save. */
data class HomePush(val version: Long, val everyone: Boolean)

/**
 * One LibraryChanged message as Home, the navbar and the Live TV guide see it. [push] is null without a
 * home version (an older plugin, or a library scan); [liveTvVersion] is Tentacle's Live TV setup run
 * ("tentacle-livetv:<version>"), null when the message isn't one.
 */
data class HomeChange(val push: HomePush?, val itemsAdded: Boolean = false, val liveTvVersion: Long? = null)

private const val HOME_VERSION_MARKER = "tentacle-home:"

/**
 * The home version in a LibraryChanged message's CollectionFolders: the plugin adds
 * "tentacle-home:<user id without dashes, or *>:<version>" (jellyfin-tentacle POST /Tentacle/Refresh).
 * Null when there is none, or it is another user's.
 */
fun homePushFrom(collectionFolders: List<String>, userId: UUID?): HomePush? {
	for (entry in collectionFolders) {
		if (!entry.startsWith(HOME_VERSION_MARKER)) continue
		val parts = entry.removePrefix(HOME_VERSION_MARKER).split(':')
		if (parts.size != 2) continue
		val version = parts[1].toLongOrNull()?.takeIf { it > 0 } ?: continue
		val who = parts[0]
		if (who == "*") return HomePush(version, everyone = true)
		if (userId != null && who.equals(userId.toString().replace("-", ""), ignoreCase = true)) {
			return HomePush(version, everyone = false)
		}
	}
	return null
}

/**
 * Live settings that never get lost (androidtv audit H2, H3). [SocketHandler] hears every
 * LibraryChanged message while the app is in front, also in the player and on other
 * screens, and reports it here; every Tentacle answer that carries a home version is noted
 * too. Home and the navbar compare [known] with what they show when they come into view,
 * when the app comes back to the front and on [reconnects], and refresh only if it moved.
 * Versions only go up (the server hands out at least the time in ms), so the newest is the
 * largest. 0 means unknown: an older plugin, whose clients behave as before.
 */
class HomeVersionTracker {
	private val _known = MutableStateFlow(0L)
	val known: StateFlow<Long> = _known.asStateFlow()

	private val _changes = MutableSharedFlow<HomeChange>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
	val changes: SharedFlow<HomeChange> = _changes.asSharedFlow()

	private val _reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

	/** The WebSocket came back after it was lost: a push may have been missed meanwhile. */
	val reconnects: SharedFlow<Unit> = _reconnects.asSharedFlow()

	fun noteVersion(version: Long) {
		if (version > 0) _known.update { maxOf(it, version) }
	}

	fun onLibraryChanged(change: HomeChange) {
		change.push?.let { noteVersion(it.version) }
		_changes.tryEmit(change)
	}

	fun onReconnected() {
		_reconnects.tryEmit(Unit)
	}
}
