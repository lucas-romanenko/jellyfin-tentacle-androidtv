package org.jellyfin.androidtv.data.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.androidtv.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for a newer version and downloads it (github flavor). The decisions
 * are pure functions in UpdateLogic.kt; the dialogs are in UpdatePrompt.
 */
class UpdateCheckerService(private val context: Context) {
	private val httpClient = OkHttpClient.Builder()
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.build()

	companion object {
		private const val GITHUB_OWNER = "lucas-romanenko"
		private const val GITHUB_REPO = "jellyfin-tentacle-androidtv"
		private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
		private const val APK_NAME = "update.apk"
	}

	/** Whether this build updates itself; debug builds and the playstore flavor don't. */
	val mode: SelfUpdateMode = selfUpdateMode(BuildConfig.ENABLE_OTA_UPDATES, BuildConfig.DEBUG, BuildConfig.APPLICATION_ID)

	/** The version installed. */
	val installedVersion: String = BuildConfig.VERSION_NAME

	private var oldDownloadDeleted = false

	/** Ask GitHub for the latest release: up to date, a newer version, or no answer. */
	suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(GITHUB_API_URL)
			.addHeader("Accept", "application/vnd.github.v3+json")
			.build()

		val result = try {
			httpClient.newCall(request).execute().use { response ->
				parseUpdateResponse(response.code, response.body?.string(), BuildConfig.VERSION_NAME)
			}
		} catch (e: Exception) {
			Timber.w(e, "Update check: no answer from GitHub")
			parseUpdateResponse(null, null, BuildConfig.VERSION_NAME)
		}
		Timber.i("Update check (installed ${BuildConfig.VERSION_NAME}): $result")
		result
	}

	/** Where the download goes: the app's external files, else its cache (both in file_provider_paths.xml). */
	private fun downloadDirs(): List<File> = listOfNotNull(
		context.getExternalFilesDir(null)?.let { File(it, "downloads") },
		File(context.cacheDir, "updates"),
	)

	/**
	 * Download the new version's APK, reporting progress 0-100 on the main thread.
	 * @return a FileProvider URI for the installer
	 */
	suspend fun downloadUpdate(
		downloadUrl: String,
		onProgress: (Int) -> Unit = {}
	): Result<Uri> = withContext(Dispatchers.IO) {
		runCatching {
			val request = Request.Builder()
				.url(downloadUrl)
				.build()

			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					throw Exception("Failed to download update: ${response.code}")
				}

				val body = response.body ?: throw Exception("Empty response body")
				val contentLength = body.contentLength()

				val downloadsDir = downloadDirs().first { dir -> dir.mkdirs() || dir.isDirectory }
				val apkFile = File(downloadsDir, APK_NAME)
				if (apkFile.exists()) apkFile.delete()

				FileOutputStream(apkFile).use { output ->
					val buffer = ByteArray(64 * 1024)
					var bytesRead: Int
					var totalBytesRead = 0L
					var lastPercent = -1

					body.byteStream().use { input ->
						while (input.read(buffer).also { bytesRead = it } != -1) {
							output.write(buffer, 0, bytesRead)
							totalBytesRead += bytesRead

							val percent = downloadPercent(totalBytesRead, contentLength)
							if (percent != null && percent != lastPercent) {
								lastPercent = percent
								withContext(Dispatchers.Main) { onProgress(percent) }
							}
						}
					}
				}

				Timber.d("Update downloaded to: ${apkFile.absolutePath}")

				FileProvider.getUriForFile(
					context,
					"${context.packageName}.fileprovider",
					apkFile
				)
			}
		}
	}

	/** Delete a download left from an earlier install attempt; once per process. */
	fun deleteOldDownload() {
		if (oldDownloadDeleted) return
		oldDownloadDeleted = true
		for (dir in downloadDirs()) {
			runCatching { File(dir, APK_NAME).takeIf { it.exists() }?.delete() }
				.onFailure { Timber.w(it, "Could not delete an old update download") }
		}
	}

	/** Hand the downloaded APK to the system installer. */
	fun installUpdate(apkUri: Uri) {
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(apkUri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		context.startActivity(intent)
	}
}
