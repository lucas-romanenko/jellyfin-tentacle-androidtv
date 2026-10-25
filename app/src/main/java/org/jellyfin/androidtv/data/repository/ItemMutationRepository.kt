package org.jellyfin.androidtv.data.repository

import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.util.apiclient.ioCall
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant

interface ItemMutationRepository {
	suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto
	suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto

	/**
	 * Off Continue Watching: the saved position goes back to 0, watched or not stays as it was.
	 * Jellyfin's resume list is "has a position", and Jellyfin Web has no other way to take a title off
	 * it (10.11).
	 */
	suspend fun clearResume(item: UUID)
}

class ItemMutationRepositoryImpl(
	private val api: ApiClient,
	private val dataRefreshService: DataRefreshService,
) : ItemMutationRepository {
	override suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto {
		val response by when {
			favorite -> api.ioCall { userLibraryApi.markFavoriteItem(itemId = item) }
			else -> api.ioCall { userLibraryApi.unmarkFavoriteItem(itemId = item) }
		}

		dataRefreshService.lastFavoriteUpdate = Instant.now()
		return response
	}

	override suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto {
		val response by when {
			played -> api.ioCall { playStateApi.markPlayedItem(itemId = item) }
			else -> api.ioCall { playStateApi.markUnplayedItem(itemId = item) }
		}

		return response
	}

	override suspend fun clearResume(item: UUID) {
		api.ioCall {
			itemsApi.updateItemUserData(itemId = item, data = UpdateUserItemDataDto(playbackPositionTicks = 0))
		}
	}
}
