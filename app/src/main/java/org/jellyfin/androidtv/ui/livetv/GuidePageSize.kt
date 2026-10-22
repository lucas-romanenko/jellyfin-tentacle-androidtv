package org.jellyfin.androidtv.ui.livetv

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService

/** Channels per guide page on most devices (LiveTvGuideFragment.PAGE_SIZE). */
const val GUIDE_PAGE_SIZE_FULL = 75

/** Channels per guide page on a small-memory TV. */
const val GUIDE_PAGE_SIZE_SMALL = 25

/**
 * Channels per guide page. Every channel is a row of inflated program cells built at once, so a
 * TV with a small app heap ([memoryClassMb] at most 256 MB) or a low-RAM device gets a shorter
 * page; the paging rows above and below the guide reach the other channels.
 */
fun guidePageSize(memoryClassMb: Int, isLowRamDevice: Boolean): Int =
	if (isLowRamDevice || memoryClassMb <= 256) GUIDE_PAGE_SIZE_SMALL else GUIDE_PAGE_SIZE_FULL

fun guidePageSize(context: Context): Int {
	val activityManager = context.getSystemService<ActivityManager>() ?: return GUIDE_PAGE_SIZE_FULL
	return guidePageSize(activityManager.memoryClass, activityManager.isLowRamDevice)
}
