package org.jellyfin.androidtv.constant

/**
 * Default values for API queries and pagination throughout the application.
 * Centralizes magic numbers to improve maintainability and consistency.
 */
object QueryDefaults {
	/**
	 * Standard page size for general item listings (e.g., continue watching, latest items)
	 */
	const val DEFAULT_PAGE_SIZE = 50

	/**
	 * Smaller page size for search results and quick queries
	 */
	const val SEARCH_PAGE_SIZE = 25

	/**
	 * Number of items to show in "because you watched" suggestions
	 */
	const val SUGGESTED_ITEMS_LIMIT = 8

	/**
	 * Number of similar items to show for each suggestion
	 */
	const val SIMILAR_ITEMS_LIMIT = 7

	/**
	 * Default chunk size for row adapters (standard density)
	 */
	const val DEFAULT_CHUNK_SIZE = 40

	/**
	 * Larger chunk size for denser row layouts
	 */
	const val LARGE_CHUNK_SIZE = 100

	/**
	 * Chunk size when no chunking is desired (load all at once)
	 */
	const val NO_CHUNK = 0
}
