package org.jellyfin.androidtv.ui.settings.screen.moonfin

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private data class GenreItem(val id: String, val name: String)

@Composable
fun SettingsMoonfinMediaBarExcludedGenresScreen() {
	val api = koinInject<ApiClient>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()
	var excludedGenresJson by rememberPreference(userSettingPreferences, UserSettingPreferences.mediaBarExcludedGenres)

	val json = remember { Json { ignoreUnknownKeys = true; isLenient = true } }

	val excludedSet = remember(excludedGenresJson) {
		try {
			val array = json.parseToJsonElement(excludedGenresJson.ifBlank { "[]" }) as? JsonArray
			array?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
		} catch (_: Exception) { emptySet() }
	}

	var genres by remember { mutableStateOf<List<GenreItem>?>(null) }
	var error by remember { mutableStateOf(false) }

	val httpClient = remember {
		OkHttpClient.Builder()
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(10, TimeUnit.SECONDS)
			.build()
	}

	LaunchedEffect(Unit) {
		withContext(Dispatchers.IO) {
			val baseUrl = api.baseUrl ?: return@withContext
			val token = api.accessToken ?: return@withContext
			try {
				val request = Request.Builder()
					.url("$baseUrl/Moonfin/Genres")
					.header("Authorization", "MediaBrowser Token=\"$token\"")
					.get()
					.build()
				val body = httpClient.newCall(request).execute().use {
					if (!it.isSuccessful) {
						error = true
						return@withContext
					}
					it.body?.string()
				}
				if (body.isNullOrBlank()) {
					error = true
					return@withContext
				}
				val root = json.decodeFromString<JsonObject>(body)
				val itemsArray = (root["Items"] ?: root["items"]) as? JsonArray
				genres = itemsArray?.mapNotNull { element ->
					val obj = element.jsonObject
					val id = (obj["Id"] ?: obj["id"])?.jsonPrimitive?.content ?: return@mapNotNull null
					val name = (obj["Name"] ?: obj["name"])?.jsonPrimitive?.content ?: return@mapNotNull null
					GenreItem(id, name)
				}?.sortedBy { it.name } ?: emptyList()
			} catch (_: Exception) {
				error = true
			}
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_media_bar_title).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres)) },
				captionContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres_summary)) },
			)
		}

		when {
			error -> {
				item {
					ListSection(
						headingContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres_error)) },
					)
				}
			}
			genres == null -> {
				item {
					ListSection(
						headingContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres_loading)) },
					)
				}
			}
			genres?.isEmpty() == true -> {
				item {
					ListSection(
						headingContent = { Text(stringResource(R.string.pref_media_bar_excluded_genres_empty)) },
					)
				}
			}
			else -> {
				items(genres!!) { genre ->
					val isExcluded = genre.id in excludedSet
					ListButton(
						headingContent = { Text(genre.name) },
						trailingContent = { Checkbox(checked = isExcluded) },
						onClick = {
							val newSet = if (isExcluded) excludedSet - genre.id else excludedSet + genre.id
							excludedGenresJson = buildJsonArray {
								newSet.forEach { add(JsonPrimitive(it)) }
							}.toString()
						}
					)
				}
			}
		}
	}
}
