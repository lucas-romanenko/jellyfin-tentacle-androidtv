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
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber

/**
 * Repository for communicating with the Tentacle Jellyfin plugin endpoints.
 *
 * The Tentacle plugin exposes custom API endpoints on the Jellyfin server:
 * - GET /TentacleHome/Sections?userId={userId} — list of home screen sections
 * - GET /TentacleHome/Section/{playlistId}?userId={userId} — items for a section
 * - GET /TentacleHome/Hero?userId={userId} — hero/spotlight items
 *
 * These endpoints return standard Jellyfin BaseItemDto objects, so the existing
 * CardPresenter and item navigation work without modification.
 */
class TentacleRepository(
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
	 * Check if the Tentacle plugin is available on the server.
	 * Caches the result for the session to avoid repeated failed requests.
	 */
	private var availabilityChecked = false
	private var isAvailable = false

	suspend fun checkAvailable(): Boolean {
		if (availabilityChecked) return isAvailable

		return withContext(Dispatchers.IO) {
			try {
				val url = buildUrl("/TentacleHome/Sections")
				val request = Request.Builder().url(url).get().build()
				val response = httpClient.newCall(request).execute()
				isAvailable = response.isSuccessful
				availabilityChecked = true

				if (isAvailable) {
					Timber.i("Tentacle plugin detected on server")
				} else {
					Timber.i("Tentacle plugin not available (HTTP ${response.code})")
				}

				response.close()
				isAvailable
			} catch (e: Exception) {
				Timber.w(e, "Tentacle plugin not reachable")
				availabilityChecked = true
				isAvailable = false
				false
			}
		}
	}

	/**
	 * Fetch the list of home screen sections from the Tentacle plugin.
	 * Returns null if the plugin is not available or returns an error.
	 */
	suspend fun getSections(): TentacleSectionsResponse? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Sections")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			val result = json.decodeFromString<TentacleSectionsResponse>(body)
			if (!result.enabled) return@withContext null

			result
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Tentacle sections")
			null
		}
	}

	/**
	 * Fetch items for a specific section/playlist.
	 * Returns standard Jellyfin BaseItemDto objects.
	 */
	suspend fun getSectionItems(playlistId: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Section/$playlistId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<BaseItemDtoQueryResult>(body)
			Timber.d("Tentacle section '$playlistId': ${result.items.size} items, first imageTags=${result.items.firstOrNull()?.imageTags}")
			result.items
		} catch (e: Exception) {
			Timber.e(e, "Failed to fetch Tentacle section items for $playlistId")
			emptyList()
		}
	}

	/**
	 * Fetch hero/spotlight items with full image data.
	 */
	suspend fun getHeroItems(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Hero")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<BaseItemDtoQueryResult>(body)
			Timber.d("Tentacle hero: ${result.items.size} items")
			result.items.forEach { item ->
				Timber.d("Tentacle hero item: '${item.name}' overview=${if (item.overview != null) "${item.overview?.take(30)}..." else "NULL"}")
			}
			result.items
		} catch (e: Exception) {
			Timber.e(e, "Failed to fetch Tentacle hero items")
			emptyList()
		}
	}

	/**
	 * Fetch discover sections (trending, popular, coming soon, etc.) from Tentacle.
	 * These are TMDB-sourced items, not Jellyfin library items.
	 */
	suspend fun getDiscoverSections(): List<DiscoverSection> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Items")
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
			Timber.w(e, "Failed to fetch Tentacle discover sections")
			emptyList()
		}
	}

	/**
	 * Search TMDB for movies/series via Tentacle.
	 */
	suspend fun searchDiscover(query: String, type: String = "all"): List<DiscoverItem> = withContext(Dispatchers.IO) {
		try {
			val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
			val baseUrl = buildUrl("/TentacleDiscover/Search")
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
			val url = buildUrl("/TentacleDiscover/Detail/$mediaType/$tmdbId")
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
	 * Get Radarr quality profiles.
	 */
	suspend fun getRadarrProfiles(): List<QualityProfile> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/RadarrProfiles")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()
			if (!response.isSuccessful) return@withContext emptyList()
			json.decodeFromString<List<QualityProfile>>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Radarr profiles")
			emptyList()
		}
	}

	/**
	 * Get Sonarr quality profiles.
	 */
	suspend fun getSonarrProfiles(): List<QualityProfile> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/SonarrProfiles")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()
			if (!response.isSuccessful) return@withContext emptyList()
			json.decodeFromString<List<QualityProfile>>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Sonarr profiles")
			emptyList()
		}
	}

	/**
	 * Add a movie to Radarr via Tentacle.
	 */
	suspend fun addToRadarr(tmdbId: Int, qualityProfileId: Int? = null): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/AddToRadarr")
			val jsonBody = buildString {
				append("""{"tmdb_ids":[$tmdbId]""")
				if (qualityProfileId != null) append(""","quality_profile_id":$qualityProfileId""")
				append("}")
			}
			Timber.d("addToRadarr: POST $url body=$jsonBody")
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()

			val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")
			response.close()

			Timber.d("addToRadarr: HTTP ${response.code} body=$body")

			if (!response.isSuccessful) {
				return@withContext AddResult(error = "HTTP ${response.code}: $body")
			}

			val result = json.decodeFromString<AddResult>(body)
			Timber.d("addToRadarr: parsed result added=${result.added} exists=${result.alreadyExists} failed=${result.failed} error=${result.error}")
			result
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId to Radarr")
			AddResult(error = e.message ?: "Unknown error")
		}
	}

	/**
	 * Add a series to Sonarr via Tentacle.
	 */
	suspend fun addToSonarr(tmdbId: Int, qualityProfileId: Int? = null): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/AddToSonarr")
			val jsonBody = buildString {
				append("""{"tmdb_ids":[$tmdbId]""")
				if (qualityProfileId != null) append(""","quality_profile_id":$qualityProfileId""")
				append("}")
			}
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
	 * Find a Jellyfin library item by searching for its title.
	 * Returns the item UUID if found, null otherwise.
	 */
	suspend fun findJellyfinItem(title: String, year: String, mediaType: String): java.util.UUID? = withContext(Dispatchers.IO) {
		try {
			val itemKind = if (mediaType == "series") BaseItemKind.SERIES else BaseItemKind.MOVIE
			val request = GetItemsRequest(
				searchTerm = title,
				includeItemTypes = setOf(itemKind),
				recursive = true,
				limit = 5,
			)
			val result = api.itemsApi.getItems(request).content
			val items = result.items

			// Try exact title + year match first
			val match = items.firstOrNull { item ->
				val itemTitle = item.name.orEmpty()
				val itemYear = item.productionYear?.toString() ?: ""
				itemTitle.equals(title, ignoreCase = true) && (year.isEmpty() || itemYear == year)
			} ?: items.firstOrNull() // Fall back to first result

			match?.id
		} catch (e: Exception) {
			Timber.w(e, "Failed to find Jellyfin item for '$title'")
			null
		}
	}

	/**
	 * Reorder Tentacle home screen sections.
	 * Sends the new playlist ID order to the server via the C# plugin proxy.
	 */
	suspend fun reorderSections(playlistIds: List<String>): Boolean = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Reorder")
			val orderJson = playlistIds.joinToString(",") { "\"$it\"" }
			val jsonBody = """{"order":[$orderJson]}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val success = response.isSuccessful
			response.close()
			if (success) Timber.i("Tentacle sections reordered successfully")
			else Timber.w("Tentacle reorder failed: HTTP ${response.code}")
			success
		} catch (e: Exception) {
			Timber.w(e, "Failed to reorder Tentacle sections")
			false
		}
	}

	/**
	 * Fetch all available playlists from Tentacle (for hero picker, etc.).
	 */
	suspend fun getAvailablePlaylists(): List<TentaclePlaylist> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Playlists")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext emptyList()
			}

			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()

			val result = json.decodeFromString<TentaclePlaylistsResponse>(body)
			result.playlists
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Tentacle playlists")
			emptyList()
		}
	}

	/**
	 * Get the current hero config (which playlist is set as hero).
	 */
	suspend fun getHeroConfig(): TentacleHeroConfig? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/HeroConfig")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			json.decodeFromString<TentacleHeroConfig>(body)
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
			val url = buildUrl("/TentacleHome/Hero")
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
	 * Fetch download activity (active downloads + unreleased items) from Tentacle.
	 */
	suspend fun getActivity(): ActivityResponse? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Activity")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()

			if (!response.isSuccessful) {
				response.close()
				return@withContext null
			}

			val body = response.body?.string() ?: return@withContext null
			response.close()

			json.decodeFromString<ActivityResponse>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Tentacle activity")
			null
		}
	}

	/**
	 * Fetch TMDB seasons for a series.
	 */
	suspend fun getSeasons(tmdbId: Int): SeasonsResponse? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Seasons/$tmdbId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			if (!response.isSuccessful) { response.close(); return@withContext null }
			val body = response.body?.string() ?: return@withContext null
			response.close()
			json.decodeFromString<SeasonsResponse>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch seasons for tmdb:$tmdbId")
			null
		}
	}

	/**
	 * Fetch TMDB episodes for a specific season.
	 */
	suspend fun getSeasonEpisodes(tmdbId: Int, seasonNumber: Int): List<TmdbEpisode> = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Season/$tmdbId/$seasonNumber")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			if (!response.isSuccessful) { response.close(); return@withContext emptyList() }
			val body = response.body?.string() ?: return@withContext emptyList()
			response.close()
			json.decodeFromString<SeasonEpisodesResponse>(body).episodes
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch episodes for tmdb:$tmdbId season $seasonNumber")
			emptyList()
		}
	}

	/**
	 * Fetch Sonarr episode monitoring state for a series.
	 */
	suspend fun getSonarrEpisodes(tmdbId: Int): SonarrEpisodesResponse = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/SonarrEpisodes/$tmdbId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			if (!response.isSuccessful) { response.close(); return@withContext SonarrEpisodesResponse() }
			val body = response.body?.string() ?: return@withContext SonarrEpisodesResponse()
			response.close()
			json.decodeFromString<SonarrEpisodesResponse>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Sonarr episodes for tmdb:$tmdbId")
			SonarrEpisodesResponse()
		}
	}

	/**
	 * Fetch VOD episodes (existing .strm files on disk) for a series.
	 */
	suspend fun getVodEpisodes(tmdbId: Int): VodEpisodesResponse = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/VodEpisodes/$tmdbId")
			val request = Request.Builder().url(url).get().build()
			val response = httpClient.newCall(request).execute()
			if (!response.isSuccessful) { response.close(); return@withContext VodEpisodesResponse() }
			val body = response.body?.string() ?: return@withContext VodEpisodesResponse()
			response.close()
			json.decodeFromString<VodEpisodesResponse>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch VOD episodes for tmdb:$tmdbId")
			VodEpisodesResponse()
		}
	}

	/**
	 * Toggle follow/unfollow for a series (syncs with Sonarr monitorNewItems).
	 */
	suspend fun toggleFollow(tmdbId: Int, follow: Boolean): FollowResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Follow/$tmdbId")
			val jsonBody = """{"follow":$follow}"""
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext FollowResult()
			response.close()
			if (!response.isSuccessful) return@withContext FollowResult()
			json.decodeFromString<FollowResult>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to toggle follow for tmdb:$tmdbId")
			FollowResult()
		}
	}

	/**
	 * Add a series to Sonarr with monitoring options and optional episode selection.
	 */
	suspend fun addToSonarrWithEpisodes(
		tmdbId: Int,
		qualityProfileId: Int? = null,
		monitor: String = "all",
		selectedEpisodes: List<SelectedEpisode>? = null,
		autoFollow: Boolean = true,
	): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/AddToSonarr")
			val jsonBody = buildString {
				append("""{"tmdb_ids":[$tmdbId]""")
				if (qualityProfileId != null) append(""","quality_profile_id":$qualityProfileId""")
				append(""","monitor":"$monitor"""")
				if (selectedEpisodes != null) {
					append(""","selected_episodes":[""")
					append(selectedEpisodes.joinToString(",") {
						"""{"season":${it.season},"episode":${it.episode}}"""
					})
					append("]")
				}
				if (autoFollow) append(""","auto_follow":true""")
				append("}")
			}
			Timber.d("addToSonarrWithEpisodes: POST body=$jsonBody")
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")
			response.close()
			if (!response.isSuccessful) return@withContext AddResult(error = "HTTP ${response.code}: $body")
			json.decodeFromString<AddResult>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId to Sonarr with episodes")
			AddResult(error = e.message ?: "Unknown error")
		}
	}

	/**
	 * Manage episode monitoring for an existing Sonarr series.
	 */
	suspend fun manageEpisodes(tmdbId: Int, selectedEpisodes: List<SelectedEpisode>): ManageEpisodesResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/ManageEpisodes")
			val jsonBody = buildString {
				append("""{"tmdb_id":$tmdbId,"selected_episodes":[""")
				append(selectedEpisodes.joinToString(",") {
					"""{"season":${it.season},"episode":${it.episode}}"""
				})
				append("]}")
			}
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			val response = httpClient.newCall(request).execute()
			val body = response.body?.string() ?: return@withContext ManageEpisodesResult()
			response.close()
			if (!response.isSuccessful) return@withContext ManageEpisodesResult()
			json.decodeFromString<ManageEpisodesResult>(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to manage episodes for tmdb:$tmdbId")
			ManageEpisodesResult()
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
data class TentacleSectionsResponse(
	val enabled: Boolean = false,
	val sections: List<TentacleSection> = emptyList(),
)

@Serializable
data class TentacleSection(
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
	// Series-specific fields (enriched by plugin from library endpoint)
	val following: Boolean? = null,
	@SerialName("series_status")
	val seriesStatus: String? = null,
	@SerialName("in_library")
	val inLibrary: Boolean = false,
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
data class QualityProfile(
	val id: Int = 0,
	val name: String = "",
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
data class TentaclePlaylistsResponse(
	val playlists: List<TentaclePlaylist> = emptyList(),
)

@Serializable
data class TentaclePlaylist(
	val name: String = "",
	@SerialName("playlist_id")
	val playlistId: String = "",
)

@Serializable
data class TentacleHeroConfig(
	val enabled: Boolean = false,
	val playlistId: String = "",
	val displayName: String = "",
)

@Serializable
data class ActivityResponse(
	val downloads: List<ActivityDownload> = emptyList(),
	val unreleased: List<ActivityUnreleased> = emptyList(),
)

@Serializable
data class ActivityDownload(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	val title: String = "",
	val year: String = "",
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("media_type")
	val mediaType: String = "movie",
	val source: String = "",
	val status: String = "",
	val progress: Double = 0.0,
	@SerialName("size_remaining")
	val sizeRemaining: String = "",
	val eta: String = "",
	val quality: String = "",
	val episode: String = "",
)

@Serializable
data class ActivityUnreleased(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	val title: String = "",
	val year: String = "",
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("media_type")
	val mediaType: String = "movie",
	val source: String = "",
	@SerialName("release_date")
	val releaseDate: String = "",
	val status: String = "",
)

// --- Seasons & Episodes ---

@Serializable
data class SeasonsResponse(
	val title: String = "",
	val seasons: List<TmdbSeason> = emptyList(),
)

@Serializable
data class TmdbSeason(
	@SerialName("season_number")
	val seasonNumber: Int? = null,
	val name: String = "",
	@SerialName("episode_count")
	val episodeCount: Int = 0,
	@SerialName("air_date")
	val airDate: String? = null,
	@SerialName("poster_path")
	val posterPath: String? = null,
)

@Serializable
data class SeasonEpisodesResponse(
	val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
	@SerialName("episode_number")
	val episodeNumber: Int = 0,
	val name: String = "",
	val overview: String = "",
	@SerialName("air_date")
	val airDate: String? = null,
	val runtime: Int? = null,
	@SerialName("still_path")
	val stillPath: String? = null,
)

@Serializable
data class SonarrEpisodesResponse(
	@SerialName("in_sonarr")
	val inSonarr: Boolean = false,
	@SerialName("sonarr_id")
	val sonarrId: Int? = null,
	val reason: String? = null,
	val episodes: List<SonarrEpisode> = emptyList(),
)

@Serializable
data class SonarrEpisode(
	val id: Int = 0,
	val seasonNumber: Int = 0,
	val episodeNumber: Int = 0,
	val monitored: Boolean = false,
	val hasFile: Boolean = false,
	val airDateUtc: String? = null,
)

@Serializable
data class VodEpisodesResponse(
	@SerialName("has_episodes")
	val hasEpisodes: Boolean = false,
	val episodes: Map<String, List<Int>> = emptyMap(),
)

@Serializable
data class FollowResult(
	val success: Boolean = false,
	val following: Boolean = false,
)

@Serializable
data class ManageEpisodesResult(
	val success: Boolean = false,
	val monitored: Int = 0,
	val searching: Int = 0,
)

data class SelectedEpisode(
	val season: Int,
	val episode: Int,
)
