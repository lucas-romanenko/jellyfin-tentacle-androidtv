package org.jellyfin.androidtv.data.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The self-updater's decisions as pure functions (github flavor): what GitHub's answer means,
 * which version is newer, which file to download, and when to ask the user. The Android side
 * (download, installer, dialogs) is in UpdateCheckerService and UpdatePrompt.
 */

/** The stable asset name every release also carries, so a fixed link always gives the newest one. */
const val STABLE_RELEASE_ASSET = "tentacle-androidtv-github-release.apk"

/** GitHub's latest release. Only the tag and the assets matter; a release without a title still decodes. */
@Serializable
data class GitHubRelease(
	@SerialName("tag_name") val tagName: String = "",
	@SerialName("name") val name: String? = null,
	@SerialName("body") val body: String? = null,
	@SerialName("html_url") val htmlUrl: String? = null,
	@SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
	@SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class GitHubAsset(
	@SerialName("name") val name: String = "",
	@SerialName("browser_download_url") val downloadUrl: String = "",
	@SerialName("size") val size: Long = 0,
)

data class UpdateInfo(
	val version: String,
	val releaseNotes: String,
	val downloadUrl: String,
	val releaseUrl: String?,
	val apkSize: Long,
)

/** Why a check gave no answer. None of these means "you're on the latest version". */
enum class UpdateCheckFailure {
	/** GitHub's limit for unauthenticated calls (403/429). */
	RATE_LIMITED,

	/** No release (404). */
	NOT_FOUND,

	/** No connection, a timeout, a server error. */
	NETWORK,

	/** An answer that isn't a release, or a newer release without a file to install yet. */
	BAD_RESPONSE,
}

sealed interface UpdateCheckResult {
	data object UpToDate : UpdateCheckResult
	data class Available(val info: UpdateInfo) : UpdateCheckResult
	data class Failed(val reason: UpdateCheckFailure) : UpdateCheckResult
}

/** Whether this build may update itself from GitHub. */
enum class SelfUpdateMode {
	/** The github flavor's release build. */
	ENABLED,

	/** A debug build (".debug" app id): the release would install as a second app; updates come from test builds. */
	DEBUG_BUILD,

	/** The playstore flavor. */
	DISABLED,
}

fun selfUpdateMode(otaEnabled: Boolean, isDebug: Boolean, applicationId: String): SelfUpdateMode = when {
	!otaEnabled -> SelfUpdateMode.DISABLED
	isDebug || applicationId.endsWith(".debug") -> SelfUpdateMode.DEBUG_BUILD
	else -> SelfUpdateMode.ENABLED
}

private val versionJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

/**
 * Semver order of two versions, a leading "v" ignored: negative if [a] is older than [b], 0 if
 * equal, positive if newer. A pre-release is older than its final release ("1.21.0-rc.1" <
 * "1.21.0"); numeric pre-release parts compare as numbers ("rc.2" < "rc.10"); build metadata
 * ("+abc") is ignored. Missing or non-numeric core parts count as 0.
 */
fun compareVersions(a: String, b: String): Int {
	val (coreA, preA) = splitVersion(a)
	val (coreB, preB) = splitVersion(b)
	for (i in 0 until maxOf(coreA.size, coreB.size)) {
		val partA = coreA.getOrElse(i) { 0 }
		val partB = coreB.getOrElse(i) { 0 }
		if (partA != partB) return partA.compareTo(partB)
	}
	return when {
		preA == null && preB == null -> 0
		preA == null -> 1
		preB == null -> -1
		else -> comparePreRelease(preA, preB)
	}
}

private fun splitVersion(version: String): Pair<List<Int>, List<String>?> {
	val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('+')
	val dash = clean.indexOf('-')
	val core = if (dash < 0) clean else clean.substring(0, dash)
	val pre = if (dash < 0) null else clean.substring(dash + 1).split('.').filter { it.isNotEmpty() }
	val coreParts = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
	return coreParts to pre?.takeIf { it.isNotEmpty() }
}

private fun comparePreRelease(a: List<String>, b: List<String>): Int {
	for (i in 0 until minOf(a.size, b.size)) {
		val numA = a[i].toLongOrNull()
		val numB = b[i].toLongOrNull()
		val result = when {
			numA != null && numB != null -> numA.compareTo(numB)
			numA != null -> -1
			numB != null -> 1
			else -> a[i].compareTo(b[i])
		}
		if (result != 0) return result
	}
	return a.size.compareTo(b.size)
}

/** Only a strictly newer release is offered. */
fun isNewerVersion(latest: String, installed: String): Boolean = compareVersions(latest, installed) > 0

/**
 * The release file to install: a "-github-release.apk" (the versioned name first, then the
 * stable [STABLE_RELEASE_ASSET]), else any other release APK. Never a debug or Play Store APK.
 */
fun pickReleaseAsset(assets: List<GitHubAsset>): GitHubAsset? {
	val apks = assets.filter { asset ->
		asset.downloadUrl.isNotBlank() &&
			asset.name.endsWith(".apk", ignoreCase = true) &&
			!asset.name.contains("debug", ignoreCase = true) &&
			!asset.name.contains("playstore", ignoreCase = true)
	}
	val github = apks.filter { it.name.endsWith("-github-release.apk", ignoreCase = true) }
	return github.firstOrNull { !it.name.equals(STABLE_RELEASE_ASSET, ignoreCase = true) }
		?: github.firstOrNull()
		?: apks.firstOrNull { it.name.contains("release", ignoreCase = true) }
}

/** What GitHub's answer to "latest release" means for the [installedVersion]; [code] null = no answer. */
fun parseUpdateResponse(code: Int?, body: String?, installedVersion: String): UpdateCheckResult {
	val failure = when {
		code == null -> UpdateCheckFailure.NETWORK
		code == 403 || code == 429 -> UpdateCheckFailure.RATE_LIMITED
		code == 404 -> UpdateCheckFailure.NOT_FOUND
		code >= 500 -> UpdateCheckFailure.NETWORK
		code !in 200..299 -> UpdateCheckFailure.BAD_RESPONSE
		else -> null
	}
	if (failure != null) return UpdateCheckResult.Failed(failure)
	val release = runCatching { versionJson.decodeFromString<GitHubRelease>(body.orEmpty()) }.getOrNull()
		?.takeIf { it.tagName.isNotBlank() }
		?: return UpdateCheckResult.Failed(UpdateCheckFailure.BAD_RESPONSE)
	val latestVersion = release.tagName.trim().removePrefix("v").removePrefix("V")
	if (!isNewerVersion(latestVersion, installedVersion)) return UpdateCheckResult.UpToDate
	// A newer release whose file isn't attached yet (the release build is still running)
	val asset = pickReleaseAsset(release.assets) ?: return UpdateCheckResult.Failed(UpdateCheckFailure.BAD_RESPONSE)
	return UpdateCheckResult.Available(
		UpdateInfo(
			version = latestVersion,
			releaseNotes = release.body?.takeIf { it.isNotBlank() }.orEmpty(),
			downloadUrl = asset.downloadUrl,
			releaseUrl = release.htmlUrl,
			apkSize = asset.size,
		)
	)
}

/** How long "Later" keeps one version from being offered again on launch. */
const val UPDATE_SNOOZE_MS = 3L * 24 * 60 * 60 * 1000

/** How old the last check the person saw may be before Home checks again. */
const val UPDATE_RECHECK_MS = 6L * 60 * 60 * 1000

/** After a check that got no answer, Home waits this long before asking GitHub again. */
const val UPDATE_RETRY_MS = 15L * 60 * 1000

/** "Later" was pressed for this [version] less than 3 days ago. A newer version is never snoozed. */
fun isUpdateSnoozed(version: String, snoozedVersion: String?, snoozedAtMs: Long, nowMs: Long): Boolean {
	if (snoozedVersion.isNullOrBlank() || compareVersions(version, snoozedVersion) != 0) return false
	val elapsed = nowMs - snoozedAtMs
	return elapsed in 0 until UPDATE_SNOOZE_MS
}

/**
 * Whether Home, now on screen, asks GitHub for a new version. Yes when the background check
 * found a [foundVersion] newer than the [installedVersion] that isn't [foundSnoozed], or when the
 * last check the person saw ([lastSeenCheckMs], 0 = never) is 6 hours old or more. Not within
 * 15 minutes of a check that got no answer ([lastFailedMs]). Not tied to the process: Android
 * starts the app in the background for its workers, and opening it later must still check.
 */
fun isHomeCheckDue(
	installedVersion: String,
	lastSeenCheckMs: Long,
	foundVersion: String?,
	foundSnoozed: Boolean,
	lastFailedMs: Long?,
	nowMs: Long,
): Boolean {
	if (lastFailedMs != null && nowMs - lastFailedMs in 0 until UPDATE_RETRY_MS) return false
	if (!foundVersion.isNullOrBlank() && !foundSnoozed && isNewerVersion(foundVersion, installedVersion)) return true
	// A clock set back counts as due
	return lastSeenCheckMs <= 0 || nowMs - lastSeenCheckMs !in 0 until UPDATE_RECHECK_MS
}

/** Percent of a download, 0-100; null when the size is unknown. */
fun downloadPercent(bytesRead: Long, contentLength: Long): Int? =
	if (contentLength <= 0) null else (bytesRead * 100 / contentLength).toInt().coerceIn(0, 100)
