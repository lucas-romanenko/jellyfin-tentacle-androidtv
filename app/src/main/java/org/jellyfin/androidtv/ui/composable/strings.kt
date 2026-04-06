package org.jellyfin.androidtv.ui.composable

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R

/**
 * Get resolution name from video dimensions.
 * Non-composable version for use in View-based code.
 */
@Suppress("MagicNumber")
fun getResolutionName(context: Context, width: Int, height: Int, interlaced: Boolean = false): String {
	val suffix = if (interlaced) "i" else "p"
	return when {
		width >= 7600 || height >= 4300 -> "8K"
		width >= 3800 || height >= 2000 -> "4K"
		width >= 2500 || height >= 1400 -> "1440$suffix"
		width >= 1800 || height >= 1000 -> "1080$suffix"
		width >= 1200 || height >= 700 -> "720$suffix"
		width >= 600 || height >= 400 -> "480$suffix"
		else -> context.getString(R.string.lbl_sd)
	}
}

/**
 * Get resolution name from video dimensions.
 * Composable version.
 */
@Composable
@Suppress("MagicNumber")
fun getResolutionName(width: Int, height: Int, interlaced: Boolean = false): String =
	getResolutionName(LocalContext.current, width, height, interlaced)
