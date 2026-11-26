package org.jellyfin.androidtv.ui.playback.segment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.model.api.MediaSegmentType

/**
 * Each person's Skip intro / Skip credits choice, set once in Tentacle (Subtitles & skipping) and
 * followed on every device: Tentacle's `skip_intro` / `skip_credits` ("button", "auto", "off") as
 * this app's segment actions. Null when the answer isn't Tentacle's (an older plugin, an error):
 * then the device's own setting stays.
 */
fun tentacleSkipActions(json: String?): Map<MediaSegmentType, MediaSegmentAction>? {
	val root = runCatching { Json.parseToJsonElement(json ?: return null).jsonObject }.getOrNull() ?: return null
	fun action(key: String): MediaSegmentAction? = when ((root[key] as? JsonPrimitive)?.content) {
		"button" -> MediaSegmentAction.ASK_TO_SKIP
		"auto" -> MediaSegmentAction.SKIP
		"off" -> MediaSegmentAction.NOTHING
		else -> null
	}
	val intro = action("skip_intro") ?: return null
	val credits = action("skip_credits") ?: return null
	return mapOf(MediaSegmentType.INTRO to intro, MediaSegmentType.OUTRO to credits)
}

/** Apply the person's choice to this device (the app's own setting follows it). */
fun MediaSegmentRepository.follow(actions: Map<MediaSegmentType, MediaSegmentAction>?) {
	actions?.forEach { (type, action) ->
		if (getDefaultSegmentTypeAction(type) != action) setDefaultSegmentTypeAction(type, action)
	}
}
