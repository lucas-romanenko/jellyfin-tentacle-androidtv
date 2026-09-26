package org.jellyfin.androidtv.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.koin.compose.koinInject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The focus colour per user, read once.
 *
 * Every card calls [focusBorderColor], and a Leanback row gives each card its own
 * composition, so a `remember` there does not share anything between cards: each newly
 * bound card constructed a UserSettingPreferences (open the prefs, run the migration
 * check, log it) on the main thread. Scrolling into unseen home rows did that ~80 times,
 * froze the UI for seconds and, on a Google TV Streamer, ended in an ANR.
 */
private val focusColorByUser = ConcurrentHashMap<String, Color>()

/** Forget the cached colour; call after the focus colour setting changes. */
fun invalidateFocusBorderColor() = focusColorByUser.clear()

/**
 * Returns the current user's selected focus border color preference.
 * Use this wherever a focus border color is needed for cards, posters, icons, or nav items.
 */
@Composable
fun focusBorderColor(): Color {
	val context = LocalContext.current
	val userRepository = koinInject<UserRepository>()
	val currentUser by userRepository.currentUser.collectAsState()
	val userId: UUID? = currentUser?.id

	return remember(userId) {
		focusColorByUser.getOrPut(userId?.toString() ?: "") {
			val prefs = UserSettingPreferences(context.applicationContext, userId)
			Color(prefs[UserSettingPreferences.focusColor].colorValue)
		}
	}
}
