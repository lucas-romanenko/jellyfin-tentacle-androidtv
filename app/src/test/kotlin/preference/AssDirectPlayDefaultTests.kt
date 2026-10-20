package org.jellyfin.androidtv.preference

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ASS direct play is on by default: off, a provider title with ASS subtitles waited minutes for
 * the server to cut them out of the remote file and never started (H1, audit 2026-10-19).
 */
class AssDirectPlayDefaultTests : FunSpec({
	test("ASS direct play defaults to on") {
		UserPreferences.assDirectPlay.defaultValue shouldBe true
	}

	test("the key is unchanged, so an explicit off is kept") {
		UserPreferences.assDirectPlay.key shouldBe "ass_enabled"
	}
})
