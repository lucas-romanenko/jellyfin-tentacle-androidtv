package org.jellyfin.androidtv.preference

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jellyfin.androidtv.constant.JellyseerrRowType

/**
 * Custom serializer for JellyseerrRowType enum.
 */
object JellyseerrRowTypeSerializer : KSerializer<JellyseerrRowType> {
	override val descriptor: SerialDescriptor = 
		PrimitiveSerialDescriptor("JellyseerrRowType", PrimitiveKind.STRING)

	override fun serialize(encoder: Encoder, value: JellyseerrRowType) {
		encoder.encodeString(value.serializedName)
	}

	override fun deserialize(decoder: Decoder): JellyseerrRowType {
		val name = decoder.decodeString()
		return JellyseerrRowType.entries.find { it.serializedName == name } 
			?: JellyseerrRowType.TRENDING
	}
}

/**
 * Configuration for a Jellyseerr discover row.
 */
@Serializable
data class JellyseerrRowConfig(
	@Serializable(with = JellyseerrRowTypeSerializer::class)
	val type: JellyseerrRowType,
	val enabled: Boolean = true,
	val order: Int = 0,
) {
	companion object {
		/**
		 * Default Jellyseerr rows configuration
		 */
		fun defaults(): List<JellyseerrRowConfig> = listOf(
			JellyseerrRowConfig(JellyseerrRowType.RECENT_REQUESTS, enabled = true, order = 0),
			JellyseerrRowConfig(JellyseerrRowType.TRENDING, enabled = true, order = 1),
			JellyseerrRowConfig(JellyseerrRowType.POPULAR_MOVIES, enabled = true, order = 2),
			JellyseerrRowConfig(JellyseerrRowType.MOVIE_GENRES, enabled = true, order = 3),
			JellyseerrRowConfig(JellyseerrRowType.UPCOMING_MOVIES, enabled = true, order = 4),
			JellyseerrRowConfig(JellyseerrRowType.STUDIOS, enabled = true, order = 5),
			JellyseerrRowConfig(JellyseerrRowType.POPULAR_SERIES, enabled = true, order = 6),
			JellyseerrRowConfig(JellyseerrRowType.SERIES_GENRES, enabled = true, order = 7),
			JellyseerrRowConfig(JellyseerrRowType.UPCOMING_SERIES, enabled = true, order = 8),
			JellyseerrRowConfig(JellyseerrRowType.NETWORKS, enabled = true, order = 9),
		)
	}
}
