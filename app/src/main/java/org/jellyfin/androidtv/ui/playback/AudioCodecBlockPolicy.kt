package org.jellyfin.androidtv.ui.playback

/**
 * Whether a stuck player ("playing, no progress") means this device's audio decoder is broken.
 *
 * The codec blocklist exists for hardware decoders that advertise a codec they cannot decode
 * (E-AC3 on some Sony Bravias): a local file plays, data arrives, the audio decoder never
 * consumes it, and the clock never moves. A stall also happens when the SERVER stops sending —
 * a transcode dies, an IPTV provider refuses the live stream — and blaming the decoder then
 * blocked AAC for good after one live-TV hiccup, so every later transcode was negotiated to
 * AC3 (or MP3, the old silent-audio case) (#51).
 */
object AudioCodecBlockPolicy {
	/** Media3 decodes these in software on every device: a stall with one of them is never a decoder wedge. */
	val SOFTWARE_DECODED: Set<String> = setOf("aac", "mp3", "mp2", "opus", "vorbis", "flac", "alac", "pcm")

	/** Buffered media ahead of a frozen playhead that shows data kept arriving. */
	const val MIN_BUFFER_AHEAD_MS = 3_000L

	/**
	 * @param transcoding the server is transcoding: the codec on hand is the SOURCE's, not what the device decodes
	 * @param remote the media comes from a remote URL (a provider .strm), whose input can stall on its own
	 * @param isLive live TV: the input stops at the provider or tuner, not in the decoder
	 * @param bufferAheadMs how far the buffer reached past the playhead when playback froze
	 */
	@JvmStatic
	fun shouldBlockCodec(codec: String?, transcoding: Boolean, remote: Boolean, isLive: Boolean, bufferAheadMs: Long): Boolean {
		val normalized = codec?.trim()?.lowercase().orEmpty()
		if (normalized.isEmpty() || normalized in SOFTWARE_DECODED) return false
		if (isLive || transcoding || remote) return false
		return bufferAheadMs >= MIN_BUFFER_AHEAD_MS
	}
}
