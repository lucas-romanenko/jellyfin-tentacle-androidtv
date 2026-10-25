package org.jellyfin.androidtv.ui.livetv.multiview

/**
 * The guide's pick mode: channels ticked in order (their mark is 1-4), OK again unticks and the
 * ones after move up. At most [max]; Start with at least [min].
 */
class MultiViewPicks(val max: Int = MAX_TILES, val min: Int = MIN_TILES) {
	private val picked = mutableListOf<String>()
	private val names = mutableMapOf<String, String>()

	val ids: List<String> get() = picked.toList()
	val count: Int get() = picked.size
	val canStart: Boolean get() = picked.size >= min

	/** 1-based mark of a channel, or 0 when it isn't picked. */
	fun numberOf(id: String): Int = picked.indexOf(id) + 1

	fun nameOf(id: String): String = names[id].orEmpty()

	fun toggle(id: String, name: String = ""): Toggle {
		if (picked.remove(id)) {
			names.remove(id)
			return Toggle.REMOVED
		}
		if (picked.size >= max) return Toggle.FULL
		picked.add(id)
		names[id] = name
		return Toggle.ADDED
	}

	fun clear() {
		picked.clear()
		names.clear()
	}

	enum class Toggle { ADDED, REMOVED, FULL }

	companion object {
		const val MAX_TILES = 4
		const val MIN_TILES = 2
	}
}

/** "2 of 4 · Start" on the pick bar's button; "Pick 2 to 4" before there are enough. */
fun startLabel(count: Int, max: Int, min: Int = MultiViewPicks.MIN_TILES): String =
	if (count >= min) "$count of $max · Start" else "$count of $max · pick ${min - count} more"

/** The D-pad moving the highlight between tiles: 2 side by side, 3-4 as a 2x2 grid. */
object TileGrid {
	fun move(from: Int, count: Int, direction: Direction): Int {
		if (count <= 1) return 0
		val col = from % 2
		val row = from / 2
		val target = when (direction) {
			Direction.LEFT -> if (col == 1) from - 1 else from
			Direction.RIGHT -> if (col == 0) from + 1 else from
			Direction.UP -> if (row == 1) from - 2 else from
			Direction.DOWN -> if (row == 0) from + 2 else from
		}
		// The 3-tile grid's empty 4th place: Right from the 3rd tile stays, Down from the 2nd
		// goes to the 3rd (the one below-left) rather than nowhere.
		return when {
			target < count -> target
			direction == Direction.DOWN && count == 3 && from == 1 -> 2
			else -> from
		}
	}

	enum class Direction { LEFT, RIGHT, UP, DOWN }
}

/** Where a tile is on screen, as fractions of the picture (the server's 1920x1080 layout). */
data class TileBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

fun tileBox(x: Int, y: Int, w: Int, h: Int, pictureWidth: Int = 1920, pictureHeight: Int = 1080) = TileBox(
	left = x.toFloat() / pictureWidth,
	top = y.toFloat() / pictureHeight,
	right = (x + w).toFloat() / pictureWidth,
	bottom = (y + h).toFloat() / pictureHeight,
)

/** The channels after removing one (null when fewer than two would stay: leave instead). */
fun withoutTile(itemIds: List<String>, index: Int): List<String>? =
	itemIds.filterIndexed { i, _ -> i != index }.takeIf { it.size >= MultiViewPicks.MIN_TILES }

/** The channels with one swapped (its place kept); null if the new one is already there. */
fun withSwapped(itemIds: List<String>, index: Int, newId: String): List<String>? =
	if (itemIds.any { it.equals(newId, ignoreCase = true) }) null
	else itemIds.mapIndexed { i, id -> if (i == index) newId else id }

/** Jellyfin's ids with or without dashes, compared as Tentacle stores them. */
fun sameId(a: String, b: String): Boolean = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)
