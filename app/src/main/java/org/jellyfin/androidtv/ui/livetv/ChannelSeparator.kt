package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.sdk.model.api.BaseItemDto

/** Characters IPTV providers draw their group headings with ("####### Sportsnet PPV #######"). */
private const val SEPARATOR_DECORATION = "#=-*_~+|•·★☆■□▪◆◇●○▬━─═"

/**
 * Whether a channel name is a provider's group heading rather than a channel, like
 * "####### Sportsnet PPV #######", "===== UK =====" or "★★ PPV ★★": decoration of at least two of
 * these characters at both ends (spaces between don't count), or nothing but decoration. A real name
 * with one mark ("#1 Hits", "|UK| Sky Sports", "*NEW* Movies") stays a channel. The server hides
 * these too; this keeps them out when it doesn't.
 */
fun isChannelSeparator(name: String?): Boolean {
	val text = name?.filterNot { it.isWhitespace() } ?: return false
	if (text.isEmpty()) return false
	val leading = text.takeWhile { it in SEPARATOR_DECORATION }.length
	if (leading == text.length) return true
	val trailing = text.takeLastWhile { it in SEPARATOR_DECORATION }.length
	return leading >= 2 && trailing >= 2
}

/** [channels] without the providers' group headings ([isChannelSeparator]). */
fun withoutChannelSeparators(channels: List<BaseItemDto>): List<BaseItemDto> =
	if (channels.none { isChannelSeparator(it.name) }) channels
	else channels.filterNot { isChannelSeparator(it.name) }
