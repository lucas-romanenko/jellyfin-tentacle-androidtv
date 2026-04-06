package org.moonfin.server.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType {
	JELLYFIN,
	EMBY;

	companion object {
		/**
		 * Detect server type from product name and version string.
		 * Falls back to version heuristic when product name is absent or unrecognized
		 * (Emby omits productName in its public system info).
		 */
		fun detect(productName: String?, version: String?): ServerType {
			if (productName?.contains("Jellyfin", ignoreCase = true) == true) return JELLYFIN
			if (productName?.contains("Emby", ignoreCase = true) == true) return EMBY
			if (version != null) {
				val parts = version.split(".")
				val major = parts.firstOrNull()?.toIntOrNull()
				if (major != null && parts.size >= 4 && major < 10) return EMBY
			}
			return JELLYFIN
		}
	}
}
