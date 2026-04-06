package org.jellyfin.androidtv.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

/**
 * All possible Jellyseerr discover rows.
 */
enum class JellyseerrRowType(
	override val serializedName: String,
	override val nameRes: Int,
) : PreferenceEnum {
	RECENT_REQUESTS("recent_requests", R.string.jellyseerr_row_recent_requests),
	TRENDING("trending", R.string.jellyseerr_row_trending),
	POPULAR_MOVIES("popular_movies", R.string.jellyseerr_row_popular_movies),
	MOVIE_GENRES("movie_genres", R.string.jellyseerr_row_movie_genres),
	UPCOMING_MOVIES("upcoming_movies", R.string.jellyseerr_row_upcoming_movies),
	STUDIOS("studios", R.string.jellyseerr_row_studios),
	POPULAR_SERIES("popular_series", R.string.jellyseerr_row_popular_series),
	SERIES_GENRES("series_genres", R.string.jellyseerr_row_series_genres),
	UPCOMING_SERIES("upcoming_series", R.string.jellyseerr_row_upcoming_series),
	NETWORKS("networks", R.string.jellyseerr_row_networks),
}
