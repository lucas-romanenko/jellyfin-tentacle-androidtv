package org.jellyfin.androidtv.ui.playback

import android.content.Context
import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

/** Choices made on a title's details apply to that title only. */
class PrePlaybackTrackSelectorTests : FunSpec({
	fun selector() = PrePlaybackTrackSelector(mockk<Context> {
		every { getSharedPreferences(any(), any()) } returns MemoryPrefs()
	})

	test("a subtitle choice on one title is not applied to the next title given an audio choice") {
		val selector = selector()
		selector.setSelectedSubtitleTrack("X", 5)
		selector.setSelectedAudioTrack("Y", 2)
		selector.getSelectedAudioTrack("Y") shouldBe 2
		selector.getSelectedSubtitleTrack("Y") shouldBe null
	}

	test("subtitles None on one title doesn't turn them off on the next title played in another version") {
		val selector = selector()
		selector.setSelectedSubtitleTrack("X", -1)
		selector.setSelectedMediaSource("Y", "source-2")
		selector.getSelectedMediaSource("Y") shouldBe "source-2"
		selector.getSelectedSubtitleTrack("Y") shouldBe null
		selector.getSelectedAudioTrack("Y") shouldBe null
	}

	test("several choices on the same title are all kept") {
		val selector = selector()
		selector.setSelectedAudioTrack("X", 1)
		selector.setSelectedSubtitleTrack("X", 3)
		selector.setSelectedMediaSource("X", "source-2")
		selector.getSelectedAudioTrack("X") shouldBe 1
		selector.getSelectedSubtitleTrack("X") shouldBe 3
		selector.getSelectedMediaSource("X") shouldBe "source-2"
		selector.getSelectedAudioTrack("Y") shouldBe null
	}
})

/** In-memory SharedPreferences; like Android's, the last edit of a key in one editor wins. */
private class MemoryPrefs : SharedPreferences {
	private val values = mutableMapOf<String, Any>()

	override fun getAll(): MutableMap<String, *> = values.toMutableMap()
	override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
	@Suppress("UNCHECKED_CAST")
	override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
	override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
	override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
	override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
	override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
	override fun contains(key: String) = key in values
	override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
	override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

	override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
		private val edits = linkedMapOf<String, Any?>() // null = remove
		private var clear = false
		override fun putString(key: String, value: String?) = apply { edits[key] = value }
		override fun putStringSet(key: String, values: MutableSet<String>?) = apply { edits[key] = values }
		override fun putInt(key: String, value: Int) = apply { edits[key] = value }
		override fun putLong(key: String, value: Long) = apply { edits[key] = value }
		override fun putFloat(key: String, value: Float) = apply { edits[key] = value }
		override fun putBoolean(key: String, value: Boolean) = apply { edits[key] = value }
		override fun remove(key: String) = apply { edits[key] = null }
		override fun clear() = apply { clear = true }
		override fun commit(): Boolean {
			apply()
			return true
		}
		override fun apply() {
			if (clear) values.clear()
			edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
		}
	}
}
