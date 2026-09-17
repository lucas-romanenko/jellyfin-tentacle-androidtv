package org.jellyfin.androidtv.util.profile

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.getSystemService
import timber.log.Timber

/**
 * Output device types that carry at most two channels in practice.
 *
 * Bluetooth A2DP/SCO/LE and analogue headsets are all stereo sinks. A TV box keeps advertising the
 * HDMI sink's surround formats (stale EDID) while audio is actually routed to one of these, so the
 * capability check passes and `AudioTrack.Builder.build()` then throws
 * `UnsupportedOperationException` — playback dies at 0 ms with "Failed to load video".
 */
private val STEREO_ONLY_DEVICE_TYPES: Set<Int> = buildSet {
	add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
	add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
	add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
	add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		add(AudioDeviceInfo.TYPE_BLE_HEADSET)
		add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
	}
}

/**
 * True when audio is currently routed to a sink that can only take stereo.
 *
 * Used to force a stereo profile for that session, so multichannel content downmixes instead of
 * failing outright. Deliberately conservative: anything uncertain returns false and leaves the
 * user's configured behaviour alone.
 */
fun isStereoOnlyAudioRoute(context: Context): Boolean {
	val audioManager = context.getSystemService<AudioManager>() ?: return false

	return try {
		val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
		val routed = outputs.filter { device ->
			device.type in STEREO_ONLY_DEVICE_TYPES && device.isActiveRoute(audioManager)
		}
		if (routed.isEmpty()) return false

		// channelCounts can be empty when the sink doesn't report them; a connected stereo-only
		// device type is signal enough on its own.
		val maxChannels = routed
			.flatMap { it.channelCounts.toList() }
			.maxOrNull()
		val stereoOnly = maxChannels == null || maxChannels <= 2
		if (stereoOnly) {
			Timber.i(
				"Audio is routed to a stereo-only sink (%s) — forcing a stereo profile for this session",
				routed.joinToString { it.typeName() },
			)
		}
		stereoOnly
	} catch (error: Exception) {
		Timber.w(error, "Could not inspect audio output devices; leaving the audio profile unchanged")
		false
	}
}

/**
 * Whether this connected device is the one audio actually goes to.
 *
 * Android has no direct "current output route" query before the routing APIs became usable, so
 * this relies on the manager's own view of the Bluetooth/wired route. A connected-but-idle sink
 * must not change the profile.
 */
@Suppress("DEPRECATION")
private fun AudioDeviceInfo.isActiveRoute(audioManager: AudioManager): Boolean = when (type) {
	AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> audioManager.isBluetoothA2dpOn
	AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> audioManager.isBluetoothScoOn
	AudioDeviceInfo.TYPE_WIRED_HEADSET,
	AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> audioManager.isWiredHeadsetOn
	// BLE audio and hearing aids take over the route whenever they are connected.
	else -> true
}

private fun AudioDeviceInfo.typeName(): String = when (type) {
	AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
	AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
	AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
	AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
	else -> "type $type"
}
