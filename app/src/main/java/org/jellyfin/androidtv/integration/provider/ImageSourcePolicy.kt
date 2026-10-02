package org.jellyfin.androidtv.integration.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/**
 * The images [ImageProvider] may load for a caller: artwork from a server the user signed in to
 * (and TMDB, where some artwork comes from), or a drawable of this app. The provider is exported
 * for the launcher's channels and global search, so any app on the device can call it; it loads
 * nothing else.
 *
 * The URL is checked with OkHttp's own parser, the one that fetches it, and the checked form is
 * what gets loaded, so both always agree on the host.
 */
object ImageSourcePolicy {
	private val extraOrigins = listOf("https://image.tmdb.org")

	/** What to load for [src], or null to show the placeholder instead. */
	fun allowedSource(src: String, serverAddresses: Collection<String>, ownPackage: String): String? {
		if (src.startsWith("android.resource:", ignoreCase = true)) {
			val uri = runCatching { URI(src) }.getOrNull() ?: return null
			return src.takeIf { uri.rawAuthority == ownPackage && uri.rawUserInfo == null }
		}
		val url = src.toHttpUrlOrNull() ?: return null
		if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
		val allowed = (serverAddresses + extraOrigins).mapNotNull { origin(it) }.toSet()
		return url.toString().takeIf { origin(url) in allowed }
	}

	/** Scheme, host and port as OkHttp understands them (default ports filled in, host lower case). */
	private fun origin(address: String): String? = address.trim().toHttpUrlOrNull()?.let(::origin)

	private fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
}
