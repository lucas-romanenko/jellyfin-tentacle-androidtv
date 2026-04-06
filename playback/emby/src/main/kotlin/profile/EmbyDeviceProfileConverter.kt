package org.moonfin.playback.emby.profile

import org.emby.client.model.CodecProfile
import org.emby.client.model.CodecType
import org.emby.client.model.ContainerProfile
import org.emby.client.model.DeviceProfile
import org.emby.client.model.DirectPlayProfile
import org.emby.client.model.DlnaProfileType
import org.emby.client.model.EncodingContext
import org.emby.client.model.ProfileCondition
import org.emby.client.model.ProfileConditionType
import org.emby.client.model.ProfileConditionValue
import org.emby.client.model.SubtitleDeliveryMethod
import org.emby.client.model.SubtitleProfile
import org.emby.client.model.TranscodingProfile
import org.jellyfin.sdk.model.api.DeviceProfile as JellyfinDeviceProfile

fun JellyfinDeviceProfile.toEmbyDeviceProfile(): DeviceProfile = DeviceProfile(
	name = name,
	maxStreamingBitrate = maxStreamingBitrate?.toLong(),
	directPlayProfiles = directPlayProfiles.map { it.toEmby() },
	transcodingProfiles = transcodingProfiles.map { it.toEmby() },
	containerProfiles = containerProfiles.map { it.toEmby() },
	codecProfiles = codecProfiles.map { it.toEmby() },
	subtitleProfiles = subtitleProfiles.map { it.toEmby() },
)

private fun org.jellyfin.sdk.model.api.DirectPlayProfile.toEmby() = DirectPlayProfile(
	container = container,
	audioCodec = audioCodec,
	videoCodec = videoCodec,
	type = type.toEmby(),
)

private fun org.jellyfin.sdk.model.api.TranscodingProfile.toEmby() = TranscodingProfile(
	container = container,
	type = type.toEmby(),
	videoCodec = videoCodec,
	audioCodec = audioCodec,
	protocol = protocol.serialName,
	context = context.toEmby(),
	copyTimestamps = copyTimestamps,
	segmentLength = segmentLength.takeIf { it > 0 },
	minSegments = minSegments.takeIf { it > 0 },
	breakOnNonKeyFrames = breakOnNonKeyFrames,
	maxAudioChannels = maxAudioChannels,
	manifestSubtitles = if (enableSubtitlesInManifest) "HLS" else null,
)

private fun org.jellyfin.sdk.model.api.ContainerProfile.toEmby() = ContainerProfile(
	type = type.toEmby(),
	conditions = conditions.map { it.toEmby() },
	container = container,
)

private fun org.jellyfin.sdk.model.api.CodecProfile.toEmby() = CodecProfile(
	type = type.toEmby(),
	conditions = conditions.map { it.toEmby() },
	applyConditions = applyConditions.map { it.toEmby() },
	codec = codec,
	container = container,
)

private fun org.jellyfin.sdk.model.api.SubtitleProfile.toEmby() = SubtitleProfile(
	format = format,
	method = method.toEmby(),
	language = language,
	container = container,
)

private fun org.jellyfin.sdk.model.api.ProfileCondition.toEmby() = ProfileCondition(
	condition = condition.toEmby(),
	property = property.toEmby(),
	value = value,
	isRequired = isRequired,
)

private fun org.jellyfin.sdk.model.api.DlnaProfileType.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.DlnaProfileType.AUDIO -> DlnaProfileType.AUDIO
	org.jellyfin.sdk.model.api.DlnaProfileType.VIDEO -> DlnaProfileType.VIDEO
	org.jellyfin.sdk.model.api.DlnaProfileType.PHOTO -> DlnaProfileType.PHOTO
	org.jellyfin.sdk.model.api.DlnaProfileType.SUBTITLE -> DlnaProfileType.VIDEO
	org.jellyfin.sdk.model.api.DlnaProfileType.LYRIC -> DlnaProfileType.AUDIO
}

private fun org.jellyfin.sdk.model.api.EncodingContext.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.EncodingContext.STREAMING -> EncodingContext.STREAMING
	org.jellyfin.sdk.model.api.EncodingContext.STATIC -> EncodingContext.STATIC
}

private fun org.jellyfin.sdk.model.api.CodecType.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.CodecType.VIDEO -> CodecType.VIDEO
	org.jellyfin.sdk.model.api.CodecType.VIDEO_AUDIO -> CodecType.VIDEO_AUDIO
	org.jellyfin.sdk.model.api.CodecType.AUDIO -> CodecType.AUDIO
}

private fun org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.ENCODE -> SubtitleDeliveryMethod.ENCODE
	org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.EMBED -> SubtitleDeliveryMethod.EMBED
	org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.EXTERNAL -> SubtitleDeliveryMethod.EXTERNAL
	org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.HLS -> SubtitleDeliveryMethod.HLS
	org.jellyfin.sdk.model.api.SubtitleDeliveryMethod.DROP -> SubtitleDeliveryMethod.ENCODE
}

private fun org.jellyfin.sdk.model.api.ProfileConditionType.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.ProfileConditionType.EQUALS -> ProfileConditionType.EQUALS
	org.jellyfin.sdk.model.api.ProfileConditionType.NOT_EQUALS -> ProfileConditionType.NOT_EQUALS
	org.jellyfin.sdk.model.api.ProfileConditionType.LESS_THAN_EQUAL -> ProfileConditionType.LESS_THAN_EQUAL
	org.jellyfin.sdk.model.api.ProfileConditionType.GREATER_THAN_EQUAL -> ProfileConditionType.GREATER_THAN_EQUAL
	org.jellyfin.sdk.model.api.ProfileConditionType.EQUALS_ANY -> ProfileConditionType.EQUALS_ANY
}

private fun org.jellyfin.sdk.model.api.ProfileConditionValue.toEmby() = when (this) {
	org.jellyfin.sdk.model.api.ProfileConditionValue.AUDIO_CHANNELS -> ProfileConditionValue.AUDIO_CHANNELS
	org.jellyfin.sdk.model.api.ProfileConditionValue.AUDIO_BITRATE -> ProfileConditionValue.AUDIO_BITRATE
	org.jellyfin.sdk.model.api.ProfileConditionValue.AUDIO_PROFILE -> ProfileConditionValue.AUDIO_PROFILE
	org.jellyfin.sdk.model.api.ProfileConditionValue.WIDTH -> ProfileConditionValue.WIDTH
	org.jellyfin.sdk.model.api.ProfileConditionValue.HEIGHT -> ProfileConditionValue.HEIGHT
	org.jellyfin.sdk.model.api.ProfileConditionValue.HAS_64_BIT_OFFSETS -> ProfileConditionValue.HAS64_BIT_OFFSETS
	org.jellyfin.sdk.model.api.ProfileConditionValue.PACKET_LENGTH -> ProfileConditionValue.PACKET_LENGTH
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_BIT_DEPTH -> ProfileConditionValue.VIDEO_BIT_DEPTH
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_BITRATE -> ProfileConditionValue.VIDEO_BITRATE
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_FRAMERATE -> ProfileConditionValue.VIDEO_FRAMERATE
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_LEVEL -> ProfileConditionValue.VIDEO_LEVEL
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_PROFILE -> ProfileConditionValue.VIDEO_PROFILE
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_TIMESTAMP -> ProfileConditionValue.VIDEO_TIMESTAMP
	org.jellyfin.sdk.model.api.ProfileConditionValue.IS_ANAMORPHIC -> ProfileConditionValue.IS_ANAMORPHIC
	org.jellyfin.sdk.model.api.ProfileConditionValue.REF_FRAMES -> ProfileConditionValue.REF_FRAMES
	org.jellyfin.sdk.model.api.ProfileConditionValue.NUM_AUDIO_STREAMS -> ProfileConditionValue.NUM_AUDIO_STREAMS
	org.jellyfin.sdk.model.api.ProfileConditionValue.NUM_VIDEO_STREAMS -> ProfileConditionValue.NUM_VIDEO_STREAMS
	org.jellyfin.sdk.model.api.ProfileConditionValue.IS_SECONDARY_AUDIO -> ProfileConditionValue.IS_SECONDARY_AUDIO
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_CODEC_TAG -> ProfileConditionValue.VIDEO_CODEC_TAG
	org.jellyfin.sdk.model.api.ProfileConditionValue.IS_AVC -> ProfileConditionValue.IS_AVC
	org.jellyfin.sdk.model.api.ProfileConditionValue.IS_INTERLACED -> ProfileConditionValue.IS_INTERLACED
	org.jellyfin.sdk.model.api.ProfileConditionValue.AUDIO_SAMPLE_RATE -> ProfileConditionValue.AUDIO_SAMPLE_RATE
	org.jellyfin.sdk.model.api.ProfileConditionValue.AUDIO_BIT_DEPTH -> ProfileConditionValue.AUDIO_BIT_DEPTH
	org.jellyfin.sdk.model.api.ProfileConditionValue.VIDEO_RANGE_TYPE -> ProfileConditionValue.VIDEO_RANGE
	org.jellyfin.sdk.model.api.ProfileConditionValue.NUM_STREAMS -> ProfileConditionValue.NUM_VIDEO_STREAMS
}
