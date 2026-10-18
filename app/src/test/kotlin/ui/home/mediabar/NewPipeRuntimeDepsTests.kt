package org.jellyfin.androidtv.ui.home.mediabar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

/**
 * NewPipe Extractor is added as a non-transitive jar (Utils.class is swapped for a local shadow),
 * so every runtime dependency in its POM has to be listed by hand. When one is missing, R8 and the
 * compiler stay quiet (-dontwarn org.schabi.newpipe.extractor.**) and the failure only shows on the
 * TV, as NoClassDefFoundError inside trailer resolution (#101).
 */
class NewPipeRuntimeDepsTests : FunSpec({
	test("a YouTube audio track's xtags parse (protobuf-javalite is on the runtime classpath)") {
		// XTags { xtags { key: "acont" value: "original" } } — what YouTube sends on the audio
		// formats of videos that have more than one audio track
		val pair = byteArrayOf(0x0a, 5) + "acont".toByteArray() + byteArrayOf(0x12, 8) + "original".toByteArray()
		val xtags = Base64.getUrlEncoder().encodeToString(byteArrayOf(0x0a, pair.size.toByte()) + pair)

		val type = Class.forName("org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper")
			.getMethod("extractAudioTrackType", String::class.java)
			.invoke(null, xtags)

		type.toString() shouldBe "ORIGINAL"
	}

	test("the extractor's JavaScript helper runs (Rhino is on the runtime classpath)") {
		val result = Class.forName("org.schabi.newpipe.extractor.utils.JavaScript")
			.getMethod("run", String::class.java, String::class.java, Array<String>::class.java)
			.invoke(null, "function f(a){return a.split('').reverse().join('')}", "f", arrayOf("abc"))

		result shouldBe "cba"
	}
})
