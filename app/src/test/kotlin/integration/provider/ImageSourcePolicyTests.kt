package org.jellyfin.androidtv.integration.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The exported image provider loads only server artwork and the app's own drawables (d1/P1). */
class ImageSourcePolicyTests : FunSpec({
	val servers = listOf("http://jf.local:8096", "https://media.example.com/jellyfin/")
	val pkg = "org.jellyfin.tentacle"
	fun allowed(src: String) = ImageSourcePolicy.allowedSource(src, servers, pkg)

	test("artwork from a signed-in server is loaded as checked") {
		allowed("http://jf.local:8096/Items/1/Images/Primary?tag=abc") shouldBe "http://jf.local:8096/Items/1/Images/Primary?tag=abc"
		allowed("https://media.example.com/jellyfin/Items/1/Images/Primary") shouldBe "https://media.example.com/jellyfin/Items/1/Images/Primary"
		allowed("https://media.example.com:443/jellyfin/Items/1/Images/Primary") shouldBe "https://media.example.com/jellyfin/Items/1/Images/Primary"
		allowed("HTTP://JF.LOCAL:8096/Items/1/Images/Primary") shouldBe "http://jf.local:8096/Items/1/Images/Primary"
		allowed("https://image.tmdb.org/t/p/w342/x.jpg") shouldBe "https://image.tmdb.org/t/p/w342/x.jpg"
	}

	test("the app's own drawables") {
		allowed("android.resource://org.jellyfin.tentacle/drawable/tile_land_tv") shouldBe "android.resource://org.jellyfin.tentacle/drawable/tile_land_tv"
		allowed("android.resource://com.other.app/drawable/x") shouldBe null
		allowed("android.resource://evil@org.jellyfin.tentacle/drawable/x") shouldBe null
	}

	test("other origins, schemes and ports are refused") {
		allowed("http://evil.example/x.png") shouldBe null
		allowed("http://192.168.1.1/x.png") shouldBe null
		allowed("http://jf.local:8097/x.png") shouldBe null
		allowed("http://jf.local/x.png") shouldBe null
		allowed("http://media.example.com/jellyfin/x.png") shouldBe null
		allowed("file:///data/user/0/org.jellyfin.tentacle/files/x.png") shouldBe null
		allowed("content://com.other.provider/x") shouldBe null
		allowed("ftp://jf.local:8096/x") shouldBe null
		allowed("not a url") shouldBe null
		allowed("") shouldBe null
	}

	test("a server name that only looks like part of the URL is refused (parsed the way it is fetched)") {
		allowed("http://evil.example\\@jf.local:8096/Items/1/Images/Primary") shouldBe null
		allowed("http://evil.example#@jf.local:8096/x") shouldBe null
		allowed("http://jf.local:8096@evil.example/x") shouldBe null
		allowed("http://user:pass@jf.local:8096/x") shouldBe null
		allowed("http://evil.example%2F@jf.local:8096/x") shouldBe null
		allowed("http://jf.local.:8096/x") shouldBe null
		allowed("http://jf.local.evil.example:8096/x") shouldBe null
		allowed("http://[::1]:8096/x") shouldBe null
	}

	test("an IPv6 server") {
		ImageSourcePolicy.allowedSource("http://[fd00::5]:8096/Items/1/Images/Primary", listOf("http://[fd00::5]:8096"), pkg) shouldBe
			"http://[fd00::5]:8096/Items/1/Images/Primary"
	}

	test("other spellings of an IP address are only the same server when OkHttp reads them as the same origin") {
		val v4 = listOf("http://192.168.1.1:8096")
		// A different host written as an IPv4-mapped IPv6 or a decimal number is refused
		ImageSourcePolicy.allowedSource("http://[::ffff:c0a8:0102]:8096/x", v4, pkg) shouldBe null
		ImageSourcePolicy.allowedSource("http://3232235778:8096/x", v4, pkg) shouldBe null
		// The server itself spelled that way reaches only the server (canonical form is what is loaded)
		val mapped = ImageSourcePolicy.allowedSource("http://[::ffff:c0a8:0101]:8096/x", v4, pkg)
		(mapped == null || mapped.startsWith("http://192.168.1.1:8096/")) shouldBe true
		val decimal = ImageSourcePolicy.allowedSource("http://3232235777:8096/x", v4, pkg)
		(decimal == null || decimal.startsWith("http://192.168.1.1:8096/")) shouldBe true
	}

	test("no server known: only the app's drawables") {
		ImageSourcePolicy.allowedSource("http://jf.local:8096/x", emptyList(), pkg) shouldBe null
		ImageSourcePolicy.allowedSource("android.resource://org.jellyfin.tentacle/drawable/x", emptyList(), pkg) shouldBe
			"android.resource://org.jellyfin.tentacle/drawable/x"
	}
})
