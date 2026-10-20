package org.jellyfin.androidtv.data.repository

import org.jellyfin.sdk.model.api.ItemFields

object ItemRepository {
	val itemFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHANNEL_INFO,
		ItemFields.CHAPTERS,
		ItemFields.CHILD_COUNT,
		ItemFields.CUMULATIVE_RUN_TIME_TICKS,
		ItemFields.DATE_CREATED,
		ItemFields.DISPLAY_PREFERENCES_ID,
		ItemFields.GENRES,
		ItemFields.ITEM_COUNTS,
		ItemFields.MEDIA_SOURCE_COUNT,
		ItemFields.MEDIA_SOURCES,
		ItemFields.MEDIA_STREAMS,
		ItemFields.OVERVIEW,
		ItemFields.PATH,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.PROVIDER_IDS,
		ItemFields.TAGLINES,
		ItemFields.TRICKPLAY,
	)

	/**
	 * The lighter set for Home's stock rows (Continue Watching, Next Up, Latest, Recently
	 * released), as in the official app 0.19.9 (#5543): no media sources, streams, chapters or
	 * trickplay, which a card never shows and the details screen fetches for itself (M1). Path
	 * stays: the "local only" card-preview policy reads it, as it does on Tentacle's own rows.
	 */
	val browseFields = setOf(
		ItemFields.CAN_DELETE,
		ItemFields.CHILD_COUNT,
		ItemFields.DATE_CREATED,
		ItemFields.GENRES,
		ItemFields.MEDIA_SOURCE_COUNT,
		ItemFields.OVERVIEW,
		ItemFields.PATH,
		ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
		ItemFields.PROVIDER_IDS,
	)
}
