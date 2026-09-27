package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidContentException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import java.io.IOException

/** A details page that cannot load its item (#18). */
class ItemLoadFailureTests : FunSpec({
	test("no answer from the server skips the fallback, which would only wait a second timeout") {
		serverAnswered(TimeoutException("Connection timed out or was interrupted", null)) shouldBe false
		serverAnswered(SecureConnectionException("bad certificate", null)) shouldBe false
		serverAnswered(ApiClientException("Unknown IO error", IOException("Socket closed"))) shouldBe false
	}

	test("a refused or unreadable item is asked for again the other way") {
		serverAnswered(InvalidStatusException(404, null)) shouldBe true
		serverAnswered(InvalidStatusException(500, null)) shouldBe true
		serverAnswered(InvalidContentException("unexpected field", null)) shouldBe true
		serverAnswered(IllegalStateException("not from the SDK")) shouldBe true
	}

	test("the page says which failure it was") {
		loadFailureFor(TimeoutException("timeout", null)) shouldBe ItemLoadFailure.UNREACHABLE
		loadFailureFor(InvalidStatusException(502, null)) shouldBe ItemLoadFailure.UNREACHABLE
		loadFailureFor(InvalidStatusException(404, null)) shouldBe ItemLoadFailure.NOT_FOUND
	}
})
