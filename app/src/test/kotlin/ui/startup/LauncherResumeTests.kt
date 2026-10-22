package org.jellyfin.androidtv.ui.startup

import android.content.Intent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The launcher icon goes back to the running app instead of the splash and a new Home. */
class LauncherResumeTests : FunSpec({
	val leanback = setOf(Intent.CATEGORY_LEANBACK_LAUNCHER)

	test("launcher icon while the app runs: back to it") {
		resumesRunningTask(Intent.ACTION_MAIN, leanback, isTaskRoot = false, sessionRunning = true) shouldBe true
		resumesRunningTask(Intent.ACTION_MAIN, setOf(Intent.CATEGORY_LAUNCHER), isTaskRoot = false, sessionRunning = true) shouldBe true
	}

	test("cold start: the task's root starts the app as before") {
		resumesRunningTask(Intent.ACTION_MAIN, leanback, isTaskRoot = true, sessionRunning = true) shouldBe false
	}

	test("no session started in this process (killed in the background, signed out): start as before") {
		resumesRunningTask(Intent.ACTION_MAIN, leanback, isTaskRoot = false, sessionRunning = false) shouldBe false
	}

	test("switch user, deep links, channel cards and the update notification aren't the launcher") {
		resumesRunningTask(null, null, isTaskRoot = false, sessionRunning = true) shouldBe false
		resumesRunningTask(Intent.ACTION_VIEW, null, isTaskRoot = false, sessionRunning = true) shouldBe false
		resumesRunningTask(Intent.ACTION_SEARCH, null, isTaskRoot = false, sessionRunning = true) shouldBe false
		resumesRunningTask(Intent.ACTION_MAIN, null, isTaskRoot = false, sessionRunning = true) shouldBe false
	}
})
