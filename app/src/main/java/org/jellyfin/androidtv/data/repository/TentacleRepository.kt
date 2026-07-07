package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
	private val context: android.content.Context,
	private val api: ApiClient,
	private val userRepository: UserRepository,
	private val httpClient: OkHttpClient,
) {
	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}

	// Short-timeout client for the availability probe only. If the plugin is slow
	// or down, the home screen must fall back fast instead of hanging on the
	// default (much longer) OkHttp timeouts.
	private val probeClient: OkHttpClient by lazy {
		httpClient.newBuilder()
			.connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
			.callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
			.build()
	}

	/** Hard ceiling on items per home row (matches backend/plugin cap). */
	private val maxRowItems = 30

	// ── On-device home cache ────────────────────────────────────────────────
	// Raw JSON bodies of the last successful home fetches, persisted per user.
	// Lets the home screen render instantly at launch (hero + rows) from disk,
	// then revalidate against the plugin in the background.

	private fun homeCacheDir(): java.io.File? {
		val userId = userRepository.currentUser.value?.id ?: return null
		return java.io.File(context.cacheDir, "tentacle-home/${userId.toString().replace("-", "")}")
	}

	private fun readHomeCache(name: String): String? = try {
		homeCacheDir()?.resolve(name)?.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
	} catch (e: Exception) {
		Timber.w(e, "Failed to read home cache $name")
		null
	}

	private fun writeHomeCache(name: String, body: String) {
		try {
			val dir = homeCacheDir() ?: return
			dir.mkdirs()
			dir.resolve(name).writeText(body)
		} catch (e: Exception) {
			Timber.w(e, "Failed to write home cache $name")
		}
	}

	private fun deleteHomeCache(name: String) {
		try {
			homeCacheDir()?.resolve(name)?.delete()
		} catch (_: Exception) { }
	}

	/** Last-known home sections from disk (null if never cached or home disabled). */
	suspend fun getCachedSections(): TentacleSectionsResponse? = withContext(Dispatchers.IO) {
		try {
			val body = readHomeCache("sections.json") ?: return@withContext null
			val result = json.decodeFromString<TentacleSectionsResponse>(body)
			if (result.enabled) result else null
		} catch (e: Exception) {
			Timber.w(e, "Failed to parse cached sections")
			null
		}
	}

	/** Last-known items for a section from disk (empty if never cached). */
	suspend fun getCachedSectionItems(playlistId: String): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val body = readHomeCache("section-$playlistId.json") ?: return@withContext emptyList()
			json.decodeFromString<BaseItemDtoQueryResult>(body).items.take(maxRowItems)
		} catch (e: Exception) {
			Timber.w(e, "Failed to parse cached section $playlistId")
			emptyList()
		}
	}

	/** Last-known hero config from disk. */
	suspend fun getCachedHeroConfig(): TentacleHeroConfig? = withContext(Dispatchers.IO) {
		try {
			val body = readHomeCache("heroconfig.json") ?: return@withContext null
			json.decodeFromString<TentacleHeroConfig>(body)
		} catch (e: Exception) {
			null
		}
	}

	/** Last-known hero items from disk (empty if never cached). */
	suspend fun getCachedHeroItems(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val body = readHomeCache("hero.json") ?: return@withContext emptyList()
			json.decodeFromString<BaseItemDtoQueryResult>(body).items
		} catch (e: Exception) {
			emptyList()
		}
	}

	/**
	 * Check if the Tentacle plugin is available on the server.
	 * Caches the result for the session to avoid repeated failed requests.
	 */
	private var availabilityChecked = false
	private var isAvailable = false

	// Activity download count for navbar badge
	private val _activityDownloadCount = MutableStateFlow(0)
	val activityDownloadCount: StateFlow<Int> = _activityDownloadCount.asStateFlow()

	// Notification flow — emits new notifications for toast display
	private val _pendingNotifications = MutableStateFlow<List<TentacleNotification>>(emptyList())
	val pendingNotifications: StateFlow<List<TentacleNotification>> = _pendingNotifications.asStateFlow()
	private val knownNotificationIds = mutableSetOf<Int>()

	fun bumpActivityDownloadCount(count: Int) {
		// Atomic read-modify-write — multiple callers (episode picker, pollers) may bump concurrently.
		_activityDownloadCount.update { it + count }
	}

	suspend fun checkAvailable(): Boolean {
		if (availabilityChecked) return isAvailable

		return withContext(Dispatchers.IO) {
			try {
				val url = buildUrl("/TentacleHome/Sections")
				val request = Request.Builder().url(url).get().build()
				probeClient.newCall(request).execute().use { response ->
					when {
						response.isSuccessful -> {
							// Definitive: plugin present and responding.
							isAvailable = true
							availabilityChecked = true
							Timber.i("Tentacle plugin detected on server")
						}
						response.code == 404 -> {
							// Definitive: plugin/endpoint absent. Cache so we stop probing.
							isAvailable = false
							availabilityChecked = true
							Timber.i("Tentacle plugin not available (HTTP 404)")
						}
						else -> {
							// Transient (5xx, 401, etc.) — don't cache, retry on next build.
							isAvailable = false
							Timber.i("Tentacle plugin probe inconclusive (HTTP ${response.code}), will retry")
						}
					}
					isAvailable
				}
			} catch (e: Exception) {
				// Transient (timeout, slow wifi, connection refused) — don't cache, retry later.
				Timber.w(e, "Tentacle plugin not reachable, will retry")
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
			if (!result.enabled) {
				// Home disabled server-side — drop the cache so the next launch
				// goes straight to the fallback sections instead of stale rows.
				deleteHomeCache("sections.json")
				return@withContext null
			}

			writeHomeCache("sections.json", body)
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
			if (result.items.isNotEmpty()) writeHomeCache("section-$playlistId.json", body)
			result.items.take(maxRowItems)
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
			if (result.items.isNotEmpty()) writeHomeCache("hero.json", body)
			else deleteHomeCache("hero.json") // Hero disabled/empty — don't render a ghost hero next launch
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
		} catch (e: kotlinx.coroutines.CancellationException) {
			// A newer keystroke cancelled this request — propagate cancellation
			// instead of swallowing it and returning an empty list.
			throw e
		} catch (e: Exception) {
			Timber.w(e, "Failed to search discover for '$query'")
			emptyList()
		}
	}

	/**
	 * Fetch full detail for a single discover item.
	 * Uses TMDB detail for items with tmdbId, or TVDB detail via Sonarr for TVDB-only items.
	 */
	suspend fun getDiscoverDetail(mediaType: String, tmdbId: Int, tvdbId: Int = 0): DiscoverDetail? = withContext(Dispatchers.IO) {
		try {
			val url = if (tmdbId > 0) {
				buildUrl("/TentacleDiscover/Detail/$mediaType/$tmdbId")
			} else {
				buildUrl("/TentacleDiscover/DetailTvdb/$tvdbId")
			}
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
			Timber.w(e, "Failed to fetch discover detail for $mediaType tmdb:$tmdbId tvdb:$tvdbId")
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
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext emptyList()
				val body = response.body?.string() ?: return@withContext emptyList()
				json.decodeFromString<List<QualityProfile>>(body)
			}
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
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext emptyList()
				val body = response.body?.string() ?: return@withContext emptyList()
				json.decodeFromString<List<QualityProfile>>(body)
			}
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
			// Don't log the URL — it carries the api_key/access token in query params.
			Timber.d("addToRadarr: POST tmdb:$tmdbId")
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			httpClient.newCall(request).execute().use { response ->
				val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")

				Timber.d("addToRadarr: HTTP ${response.code} body=$body")

				if (!response.isSuccessful) {
					return@withContext AddResult(error = "HTTP ${response.code}: $body")
				}

				val result = json.decodeFromString<AddResult>(body)
				Timber.d("addToRadarr: parsed result added=${result.added} exists=${result.alreadyExists} failed=${result.failed} error=${result.error}")
				result
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId to Radarr")
			AddResult(error = e.message ?: "Unknown error")
		}
	}

	/**
	 * Add a series to Sonarr via Tentacle.
	 */
	suspend fun addToSonarr(tmdbId: Int, qualityProfileId: Int? = null, tvdbId: Int = 0): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/AddToSonarr")
			val jsonBody = buildString {
				if (tmdbId > 0) {
					append("""{"tmdb_ids":[$tmdbId]""")
				} else {
					append("""{"tvdb_ids":[$tvdbId]""")
				}
				if (qualityProfileId != null) append(""","quality_profile_id":$qualityProfileId""")
				append("}")
			}
			val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
			val request = Request.Builder().url(url).post(requestBody).build()
			httpClient.newCall(request).execute().use { response ->
				val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")

				if (!response.isSuccessful) {
					return@withContext AddResult(error = "HTTP ${response.code}")
				}

				json.decodeFromString<AddResult>(body)
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId tvdb:$tvdbId to Sonarr")
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

			// Only return an unambiguous exact title (+ year) match. Falling back to the
			// first search result risks navigating to the wrong item, so return null instead
			// and let the caller handle "not found".
			val match = items.firstOrNull { item ->
				val itemTitle = item.name.orEmpty()
				val itemYear = item.productionYear?.toString() ?: ""
				itemTitle.equals(title, ignoreCase = true) && (year.isEmpty() || itemYear == year)
			}

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

			val result = json.decodeFromString<TentacleHeroConfig>(body)
			writeHomeCache("heroconfig.json", body)
			result
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch hero config")
			null
		}
	}

	/**
	 * Fetch the toolbar button config.
	 *
	 * Returns null on a fetch FAILURE (network error, non-200) so the caller can
	 * fall back to the default button set instead of rendering an empty toolbar.
	 * Returns an actual (possibly empty) list only when the plugin responds successfully,
	 * so a genuinely-empty config is honoured.
	 */
	suspend fun getToolbarConfig(): List<ToolbarButton>? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleHome/Toolbar")
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				val body = response.body?.string() ?: return@withContext null
				json.decodeFromString<ToolbarResponse>(body).buttons
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch toolbar config")
			null
		}
	}

	/**
	 * Clear all plugin-side caches (home config, playlist items, discover, etc.)
	 * so subsequent fetches return fresh data.
	 */
	suspend fun refreshPluginCache() = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/Tentacle/Refresh")
			val request = Request.Builder().url(url)
				.post("".toRequestBody(null))
				.build()
			val response = httpClient.newCall(request).execute()
			response.close()
			Timber.d("Plugin cache refresh: ${response.code}")
		} catch (e: Exception) {
			Timber.w(e, "Failed to refresh plugin cache")
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
	 * Notify Tentacle that an item was deleted from Jellyfin, so it can remove the DB record.
	 */
	suspend fun deleteLibraryItem(mediaType: String, tmdbId: Int, jellyfinItemId: String? = null): Boolean = withContext(Dispatchers.IO) {
		try {
			var url = buildUrl("/TentacleDiscover/LibraryItem/$mediaType/$tmdbId")
			if (jellyfinItemId != null) url += "&jellyfinItemId=$jellyfinItemId"
			val request = Request.Builder().url(url).delete().build()
			val response = httpClient.newCall(request).execute()
			val success = response.isSuccessful
			response.close()
			if (success) Timber.i("Deleted $mediaType $tmdbId from Tentacle DB")
			else Timber.w("Failed to delete from Tentacle: HTTP ${response.code}")
			success
		} catch (e: Exception) {
			Timber.w(e, "Failed to delete from Tentacle DB")
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
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				val body = response.body?.string() ?: return@withContext null

				val result = json.decodeFromString<ActivityResponse>(body)
				_activityDownloadCount.value = result.downloads.size
				result
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch Tentacle activity")
			null
		}
	}

	/**
	 * Poll for new download notifications. Returns new unseen notifications
	 * and updates the pendingNotifications flow for UI consumption.
	 */
	suspend fun pollNotifications(): NotificationsResponse? = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Notifications")
			val request = Request.Builder().url(url).get().build()
			val result = httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				val body = response.body?.string() ?: return@withContext null
				json.decodeFromString<NotificationsResponse>(body)
			}

			if (result.notificationsEnabled) {
				val newNotifs = synchronized(knownNotificationIds) {
					val fresh = result.notifications.filter { it.id !in knownNotificationIds }
					knownNotificationIds.addAll(fresh.map { it.id })
					fresh
				}
				if (newNotifs.isNotEmpty()) {
					_pendingNotifications.update { it + newNotifs }
				}
			}
			result
		} catch (e: Exception) {
			Timber.w(e, "Failed to poll Tentacle notifications")
			null
		}
	}

	/**
	 * Dismiss a notification after it's been shown as a toast.
	 */
	suspend fun dismissNotification(notificationId: Int) = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/Notifications/$notificationId/Dismiss")
			val request = Request.Builder().url(url).post("".toRequestBody()).build()
			httpClient.newCall(request).execute().close()
		} catch (e: Exception) {
			Timber.w(e, "Failed to dismiss notification $notificationId")
		}
	}

	/**
	 * Remove a notification from the pending queue (after toast is shown).
	 */
	fun consumeNotification(notificationId: Int) {
		_pendingNotifications.value = _pendingNotifications.value.filter { it.id != notificationId }
	}

	/**
	 * Fetch seasons for a series. Uses TVDB endpoint for TVDB-only items (tmdbId=0).
	 */
	suspend fun getSeasons(tmdbId: Int, tvdbId: Int = 0): SeasonsResponse? = withContext(Dispatchers.IO) {
		try {
			val path = if (tmdbId > 0) "/TentacleDiscover/Seasons/$tmdbId"
				else "/TentacleDiscover/SeasonsTvdb/$tvdbId"
			val url = buildUrl(path)
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext null
				val body = response.body?.string() ?: return@withContext null
				json.decodeFromString<SeasonsResponse>(body)
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch seasons for tmdb:$tmdbId tvdb:$tvdbId")
			null
		}
	}

	/**
	 * Fetch episodes for a specific season. Uses TVDB endpoint for TVDB-only items (tmdbId=0).
	 */
	suspend fun getSeasonEpisodes(tmdbId: Int, seasonNumber: Int, tvdbId: Int = 0): List<TmdbEpisode> = withContext(Dispatchers.IO) {
		try {
			val path = if (tmdbId > 0) "/TentacleDiscover/Season/$tmdbId/$seasonNumber"
				else "/TentacleDiscover/SeasonTvdb/$tvdbId/$seasonNumber"
			val url = buildUrl(path)
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext emptyList()
				val body = response.body?.string() ?: return@withContext emptyList()
				json.decodeFromString<SeasonEpisodesResponse>(body).episodes
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to fetch episodes for tmdb:$tmdbId tvdb:$tvdbId season $seasonNumber")
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
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext SonarrEpisodesResponse()
				val body = response.body?.string() ?: return@withContext SonarrEpisodesResponse()
				json.decodeFromString<SonarrEpisodesResponse>(body)
			}
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
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) return@withContext VodEpisodesResponse()
				val body = response.body?.string() ?: return@withContext VodEpisodesResponse()
				json.decodeFromString<VodEpisodesResponse>(body)
			}
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
			httpClient.newCall(request).execute().use { response ->
				val body = response.body?.string() ?: return@withContext FollowResult()
				if (!response.isSuccessful) return@withContext FollowResult()
				json.decodeFromString<FollowResult>(body)
			}
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
		tvdbId: Int = 0,
	): AddResult = withContext(Dispatchers.IO) {
		try {
			val url = buildUrl("/TentacleDiscover/AddToSonarr")
			val jsonBody = buildString {
				if (tmdbId > 0) {
					append("""{"tmdb_ids":[$tmdbId]""")
				} else {
					append("""{"tvdb_ids":[$tvdbId]""")
				}
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
			httpClient.newCall(request).execute().use { response ->
				val body = response.body?.string() ?: return@withContext AddResult(error = "Empty response")
				if (!response.isSuccessful) return@withContext AddResult(error = "HTTP ${response.code}: $body")
				json.decodeFromString<AddResult>(body)
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to add tmdb:$tmdbId tvdb:$tvdbId to Sonarr with episodes")
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
			httpClient.newCall(request).execute().use { response ->
				val body = response.body?.string() ?: return@withContext ManageEpisodesResult()
				if (!response.isSuccessful) return@withContext ManageEpisodesResult()
				json.decodeFromString<ManageEpisodesResult>(body)
			}
		} catch (e: Exception) {
			Timber.w(e, "Failed to manage episodes for tmdb:$tmdbId")
			ManageEpisodesResult()
		}
	}

	/**
	 * Reset the availability cache (e.g. after server reconnect).
	 * Also clears per-user notification state so a switched-in user doesn't
	 * inherit the previous user's dedupe set or pending toasts.
	 */
	fun resetAvailabilityCache() {
		availabilityChecked = false
		isAvailable = false
		_activityDownloadCount.value = 0
		synchronized(knownNotificationIds) { knownNotificationIds.clear() }
		_pendingNotifications.value = emptyList()
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
	@SerialName("tvdb_id")
	val tvdbId: Int = 0,
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
	val source: String? = null,
)

@Serializable
data class DiscoverDetail(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	@SerialName("tvdb_id")
	val tvdbId: Int = 0,
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
	@SerialName("can_delete")
	val canDelete: Boolean = false,
	@SerialName("trailer_url")
	val trailerUrl: String? = null,
	val source: String? = null,
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
	val trailerAudio: Boolean = true,
	val itemCount: Int = 10,
)

@Serializable
data class ToolbarButton(
	val id: String = "",
	val enabled: Boolean = true,
)

@Serializable
data class ToolbarResponse(
	val buttons: List<ToolbarButton> = emptyList(),
)

@Serializable
data class ActivityResponse(
	val downloads: List<ActivityDownload> = emptyList(),
	val unreleased: List<ActivityUnreleased> = emptyList(),
	@SerialName("recently_downloaded")
	val recentlyDownloaded: List<ActivityRecentlyDownloaded> = emptyList(),
)

@Serializable
data class ActivityRecentlyDownloaded(
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	val title: String = "",
	val year: String = "",
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("media_type")
	val mediaType: String = "movie",
	val episode: String = "",
	@SerialName("hours_remaining")
	val hoursRemaining: Int = 24,
	@SerialName("jellyfin_item_id")
	val jellyfinItemId: String = "",
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

@Serializable
data class NotificationsResponse(
	val notifications: List<TentacleNotification> = emptyList(),
	@SerialName("notifications_enabled")
	val notificationsEnabled: Boolean = true,
)

@Serializable
data class TentacleNotification(
	val id: Int = 0,
	@SerialName("tmdb_id")
	val tmdbId: Int = 0,
	@SerialName("media_type")
	val mediaType: String = "movie",
	val title: String = "",
	val message: String = "",
	@SerialName("poster_path")
	val posterPath: String? = null,
	@SerialName("jellyfin_item_id")
	val jellyfinItemId: String? = null,
	@SerialName("created_at")
	val createdAt: String? = null,
)
