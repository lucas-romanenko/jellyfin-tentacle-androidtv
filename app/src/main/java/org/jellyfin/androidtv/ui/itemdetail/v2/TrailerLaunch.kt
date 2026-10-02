package org.jellyfin.androidtv.ui.itemdetail.v2

import androidx.lifecycle.Lifecycle

/**
 * Whether a trailer looked up for a page may still open (#45). Only while that page is in
 * front: opening another screen only detaches it (its lookup kept running and pushed the trailer
 * over the new screen), and Back or the Home button leave it below RESUMED as well.
 */
fun trailerMayOpen(pageState: Lifecycle.State): Boolean = pageState.isAtLeast(Lifecycle.State.RESUMED)
