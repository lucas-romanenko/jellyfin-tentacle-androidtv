@file:JvmName("GuideRowPacing")

package org.jellyfin.androidtv.ui.livetv

import kotlin.math.floor

/**
 * Main-thread time the guide gives one frame for adding rows (adding them, then the layout and
 * draw that follow). A cold row (code still interpreted, first text layouts) measured about
 * 100 ms on a Sony TV; a warm one a few ms.
 */
const val GUIDE_ROWS_FRAME_BUDGET_MS = 50

/**
 * How many rows go on screen in the next frame. [msPerRow]: what a row cost in the last frame
 * that added rows (null when not measured yet: one row, then measure). [previousRows]: the rows
 * that frame added (null: none yet), so a single cheap frame at most doubles the next one.
 * Always at least one row and at most [max] (a screenful).
 */
fun guideRowsPerFrame(msPerRow: Double?, previousRows: Int?, budgetMs: Int, max: Int): Int {
	val most = maxOf(max, 1)
	if (msPerRow == null) return 1
	var rows = floor(budgetMs / maxOf(msPerRow, 0.5)).toInt()
	if (previousRows != null) rows = minOf(rows, maxOf(previousRows, 1) * 2)
	return rows.coerceIn(1, most)
}

/** What one row cost when [rows] rows took [frameMs] of main-thread time; null for no rows. */
fun guideRowCost(frameMs: Long, rows: Int): Double? =
	if (rows <= 0) null else maxOf(frameMs, 0L).toDouble() / rows
