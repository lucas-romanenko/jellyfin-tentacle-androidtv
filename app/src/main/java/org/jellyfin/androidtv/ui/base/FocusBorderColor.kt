package org.jellyfin.androidtv.ui.base

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jellyfin.androidtv.preference.FixedLook

/**
 * The focus colour for cards, posters, icons and nav items: Tentacle's accent, the same for
 * everyone ([FixedLook]).
 */
@Composable
fun focusBorderColor(): Color = FixedLook.FOCUS_COLOR
