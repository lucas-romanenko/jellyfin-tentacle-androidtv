package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import timber.log.Timber

/**
 * Repository for communicating with the MediaHub Jellyfin plugin endpoints.
 *
 * The MediaHub plugin exposes custom API endpoints on the Jellyfin server:
 * - GET /MediaHubHome/Sections?userId={userId} — list of home screen sections
 * - GET /MediaHubHome/Section/{playlistId}?userId={userId} — items for a section
 * - GET /MediaHubHome/Hero?userId={userId} — hero/spotlight items
 *
 * These endpoints return standard Jellyfin BaseItemDto objects, so the existing
 * CardPresenter and item navigation work without modification.
 */
class MediaHubRepository(
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val httpClient: OkHttpClient,
) {
	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}

	/**
	 * Check if the MediaHub plugin is available on the server.
	 * Caches the result for the session to avoid repeated failed requests.
	 */
	private var availabilityChecked = false
	private var isAvailable = false

	suspend fun checkAvailable(): Boolean {
		if (availabilityChecked) return isAvailable

		return withContext(Dispatchers.IO) {
			try {
				val url = buildUrl("/MediaHubHome/Sections")
				val request = Request.Builder().url(url).get().build()
				val response = httpClient.newCall(request).execute()
				isAvailable = response.isSuccessful
				availabilityChecked = true

				if (isAvailable) {
					Timber.i("MediaHub plugin detected on server")
				} else {
					Timber.i("MediaHub plugin not available (HTTP ${response.code})")
				}

				response.close()
				isAvailable
			} catch (e: Exception) {
				Timber.w(e, "MediaHub plugin not reachable")
				availabilityChecked = true
				isAvailable = false
				false
			}
		}
	}

	/**
	 * Fetch the list of home screen sections from the MediaHub plugin.
	 * Returns null if the plugin is not available or returns an error.
	 */
	suspend fun getSections(): MediaHubSectionsResponse? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Sections")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			val result = json.decodeFromString<MediaHubSectionsResponse>(body)
			if (!result.enabled) return@withContext null

			result
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch MediaHub sections")
			null
		}
	}

	/**
	 * Fetch items for a specific section/playlist.
	 * Returns standard Jellyfin BaseItemDto objects.
	 */
	suspend fun getSectionItems(playlistId: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Section/$playlistId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<BaseItemDtoQueryResult>(body)
			Timber.d("MediaHub section '$playlistId': ${result.items.size} items, first imageTags=${result.items.firstOrNull()?.imageTags}")
			result.items
		} catch (e: Exception) {
			Timber.e(e, "Failed to fetch MediaHub section items for $playlistId")
			emptyList()
		}
	}

	/**
	 * Fetch hero/spotlight items with full image data.
	 */
	suspend fun getHeroItems(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Hero")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<BaseItemDtoQueryResult>(body)
			Timber.d("MediaHub hero: ${result.items.size} items")
			result.items
		} catch (e: Exception) {
			Timber.e(e, "Failed to fetch MediaHub hero items")
			emptyList()
		}
	}

	/**
	 * Fetch discover sections (trending, popular, coming soon, etc.) from MediaHub.
	 * These are TMDB-sourced items, not Jellyfin library items.
	 */
	suspend fun getDiscoverSections(): List<DiscoverSection> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubDiscover/Items")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<DiscoverResponse>(body)
			result.sections
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch MediaHub discover sections")
			emptyList()
		}
	}

	/**
	 * Search TMDB for movies/series via MediaHub.
	 */
	suspend fun searchDiscover(query: String, type: String = "all"): List<DiscoverItem> = withContext(Dispatchers.IO) {
		try {
			val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
			val baseUrl = buildUrl("/MediaHubDiscover/Search")
			val url = "$baseUrl&q=$encodedQuery&type=$type"
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<DiscoverSearchResponse>(body)
			result.items
		} catch (e: Exception) {
			Timber.w(e, "Failed to search discover for '$query'")
			emptyList()
		}
	}

	/**
	 * Fetch full TMDB detail for a single discover item.
	 */
	suspend fun getDiscoverDetail(mediaType: String, tmdbId: Int): DiscoverDetail? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubDiscover/Detail/$mediaType/$tmdbId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			json.decodeFromString<DiscoverDetail>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch discover detail for $mediaType/$tmdbId")
			null
		}
	}

	/**
	 * Add a movie to Radarr via MediaHub.
	 */
	suspend fun addToRadarr(tmdbId: Int): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubDiscover/AddToRadarr")
			val jsonBody = """{"tmdb_ids":[$tmdbId]}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()

			val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")
			response.close()

			if (!response.isSuccessful) {
				return@withContext AddResult(error = "HTTP ${response.code}")
			}

			json.decodeFromString<AddResult>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId to Radarr")
			AddResult(error = e.message ?: "Unknown error")
		}
	}

	/**
	 * Add a series to Sonarr via MediaHub.
	 */
	suspend fun addToSonarr(tmdbId: Int): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubDiscover/AddToSonarr")
			val jsonBody = """{"tmdb_ids":[$tmdbId]}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()

			val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")
			response.close()

			if (!response.isSuccessful) {
				return@withContext AddResult(error = "HTTP ${response.code}")
			}

			json.decodeFromString<AddResult>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId to Sonarr")
			AddResult(error = e.message ?: "Unknown error")
		}
	}

	/**
	 * Reorder MediaHub home screen sections.
	 * Sends the new playlist ID order to the server via the C# plugin proxy.
	 */
	suspend fun reorderSections(playlistIds: List<String>): Boolean = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Reorder")
			val orderJson = playlistIds.joinToString(",") { "\"$it\"" }
			val jsonBody = """{"order":[$orderJson]}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val success = response.isSuccessful
			response.close()
			if (success) Timber.i("MediaHub sections reordered successfully")
			else Timber.w("MediaHub reorder failed: HTTP ${response.code}")
			success
		} catch (e: Exception) {
			Timber.w(e, "Failed to reorder MediaHub sections")
			false
		}
	}

	/**
	 * Fetch all available playlists from MediaHub (for hero picker, etc.).
	 */
	suspend fun getAvailablePlaylists(): List<MediaHubPlaylist> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Playlists")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<MediaHubPlaylistsResponse>(body)
			result.playlists
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch MediaHub playlists")
			emptyList()
		}
	}

	/**
	 * Get the current hero config (which playlist is set as hero).
	 */
	suspend fun getHeroConfig(): MediaHubHeroConfig? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/HeroConfig")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			json.decodeFromString<MediaHubHeroConfig>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch hero config")
			null
		}
	}

	/**
	 * Set the hero playlist. Pass empty string to disable hero.
	 */
	suspend fun setHeroPlaylist(playlistId: String): Boolean = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/MediaHubHome/Hero")
			val jsonBody = """{"playlist_id":"$playlistId"}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val success = response.isSuccessful
			response.close()
			if (success) Timber.i("Hero playlist set to: ${playlistId.ifEmpty { "(disabled)" }}")
			else Timber.w("Failed to set hero playlist: HTTP ${response.code}")
			success
		} catch (e: Exception) {
			Timber.w(e, "Failed to set hero playlist")
			false
		}
	}

	/**
	 * Reset the availability cache (e.g. after server reconnect).
	 */
	fun resetAvailabilityCache() {
		availabilityChecked = false
		isAvailable = false
	}

	private fun buildUrl(path: String): String {
		val baseUrl = api.baseUrl?.trimEnd('/') ?: throw IllegalStateException("API base URL not set")
		val userId = userRepository.currentUser.value?.id ?: throw IllegalStateException("User ID not set")
		val token = api.accessToken ?: throw IllegalStateException("Access token not set")
		return "$baseUrl$path?userId=$userId&api_key=$token"
	}
}

@Serializable
data class MediaHubSectionsResponse(
	val enabled: Boolean = false,
	val sections: List<MediaHubSection> = emptyList(),
)

@Serializable
data class MediaHubSection(
	val id: String = "",
	val type: String = "",
	val displayText: String = "",
	val playlistId: String? = null,
	val sectionId: String? = null,
)

@Serializable
data class QueryResultResponse(
	@SerialName("Items")
	val items: List<BaseItemDto> = emptyList(),
	@SerialName("TotalRecordCount")
	val totalRecordCount: Int = 0,
)

@Serializable
data class DiscoverResponse(
	val sections: List<DiscoverSection> = emptyList(),
)

@Serializable
data class DiscoverSection(
	val id: String = "",
	val title: String = "",
	val items: List<DiscoverItem> = emptyList(),
)

@Serializable
data class DiscoverItem(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	val title: String = "",
	val year: String = "",
	val overview: String = "",
	val rating: Double = 0.0,
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("backdrop_path")
	val backdropPath: String? = null,
	@SerialName("media_type")
	val mediaType: String = "movie",
	@SerialName("in_library")
	val inLibrary: Boolean = false,
)

@Serializable
data class DiscoverDetail(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	val title: String = "",
	val year: String? = null,
	val overview: String = "",
	val runtime: Int? = null,
	val rating: Double = 0.0,
	@SerialName("vote_count")
	val voteCount: Int = 0,
	val genres: List<String> = emptyList(),
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("backdrop_path")
	val backdropPath: String? = null,
	val tagline: String = "",
	val status: String = "",
	val cast: List<CastMember> = emptyList(),
	val directors: List<String> = emptyList(),
	@SerialName("media_type")
	val mediaType: String = "movie",
)

@Serializable
data class CastMember(
	val name: String = "",
	val character: String = "",
)

@Serializable
data class DiscoverSearchResponse(
	val items: List<DiscoverItem> = emptyList(),
)

@Serializable
data class AddResult(
	val added: Int = 0,
	@SerialName("already_exists")
	val alreadyExists: Int = 0,
	val failed: Int = 0,
	val error: String? = null,
)

@Serializable
data class MediaHubPlaylistsResponse(
	val playlists: List<MediaHubPlaylist> = emptyList(),
)

@Serializable
data class MediaHubPlaylist(
	val name: String = "",
	@SerialName("playlist_id")
	val playlistId: String = "",
)

@Serializable
data class MediaHubHeroConfig(
	val enabled: Boolean = false,
	val playlistId: String = "",
	val displayName: String = "",
)
