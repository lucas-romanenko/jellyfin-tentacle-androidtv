package org.jellyfin.androidtv.ui.playback.segment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaSegmentType

/** The person's Skip intro / Skip credits choice from Tentacle becomes this device's segment actions. */
class TentacleSkipChoiceTests : FunSpec({
	test("button, by itself and off") {
		tentacleSkipActions("""{"subtitle_languages":["eng"],"skip_intro":"auto","skip_credits":"button"}""") shouldBe
			mapOf(MediaSegmentType.INTRO to MediaSegmentAction.SKIP, MediaSegmentType.OUTRO to MediaSegmentAction.ASK_TO_SKIP)
		tentacleSkipActions("""{"skip_intro":"off","skip_credits":"off"}""") shouldBe
			mapOf(MediaSegmentType.INTRO to MediaSegmentAction.NOTHING, MediaSegmentType.OUTRO to MediaSegmentAction.NOTHING)
	}

	test("anything else leaves the device's own setting") {
		tentacleSkipActions(null) shouldBe null
		tentacleSkipActions("") shouldBe null
		tentacleSkipActions("not json") shouldBe null
		tentacleSkipActions("""{"detail":"Not found"}""") shouldBe null
		tentacleSkipActions("""{"skip_intro":"sometimes","skip_credits":"auto"}""") shouldBe null
	}
})
