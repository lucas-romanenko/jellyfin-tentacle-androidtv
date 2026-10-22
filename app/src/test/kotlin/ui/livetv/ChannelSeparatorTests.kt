package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Provider group headings like "####### Sportsnet PPV #######" were listed as channels. */
class ChannelSeparatorTests : FunSpec({
	test("decoration at both ends is a heading") {
		isChannelSeparator("####### Sportsnet PPV #######") shouldBe true
		isChannelSeparator("===== UK =====") shouldBe true
		isChannelSeparator("--- SPORTS ---") shouldBe true
		isChannelSeparator("** Movies **") shouldBe true
		isChannelSeparator("★★ PPV ★★") shouldBe true
		isChannelSeparator("#####Sportsnet PPV#####") shouldBe true
		isChannelSeparator("  ## CA | NEWS ##  ") shouldBe true
		isChannelSeparator("-= Retro =-") shouldBe true
	}

	test("nothing but decoration is a heading") {
		isChannelSeparator("##########") shouldBe true
		isChannelSeparator("- - - -") shouldBe true
		isChannelSeparator("#") shouldBe true
	}

	test("real channel names stay channels") {
		isChannelSeparator("TSN 1") shouldBe false
		isChannelSeparator("Sportsnet PPV") shouldBe false
		isChannelSeparator("#1 Hits") shouldBe false
		isChannelSeparator("|UK| Sky Sports") shouldBe false
		isChannelSeparator("*NEW* Movies") shouldBe false
		isChannelSeparator("BBC One -") shouldBe false
		isChannelSeparator("## Sports") shouldBe false
		isChannelSeparator("Sports ##") shouldBe false
		isChannelSeparator("CTV Calgary News") shouldBe false
		isChannelSeparator("A&E") shouldBe false
	}

	test("no name is not a heading") {
		isChannelSeparator(null) shouldBe false
		isChannelSeparator("") shouldBe false
		isChannelSeparator("   ") shouldBe false
	}

	test("a list loses only its headings, in order") {
		fun channel(name: String) = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL, name = name)
		val tsn = channel("TSN 1")
		val heading = channel("####### Sportsnet PPV #######")
		val ppv = channel("Sportsnet PPV 1")
		withoutChannelSeparators(listOf(tsn, heading, ppv)) shouldBe listOf(tsn, ppv)
		val clean = listOf(tsn, ppv)
		(withoutChannelSeparators(clean) === clean) shouldBe true
	}
})
