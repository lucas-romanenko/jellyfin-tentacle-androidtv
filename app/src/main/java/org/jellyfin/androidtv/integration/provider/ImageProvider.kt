package org.jellyfin.androidtv.integration.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.error
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException

class ImageProvider : ContentProvider() {
	private val imageLoader by inject<ImageLoader>()
	private val authenticationStore by inject<AuthenticationStore>()
	private val api by inject<ApiClient>()

	override fun onCreate(): Boolean = true

	override fun getType(uri: Uri) = null
	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
	override fun insert(uri: Uri, values: ContentValues?) = null
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
		val src = requireNotNull(uri.getQueryParameter("src"))
		// Only artwork from a signed-in server (or this app's own drawables): the provider is
		// exported for the launcher, so any app can ask it for a URL
		val servers = authenticationStore.getServers().values.map { it.address } + listOfNotNull(api.baseUrl)
		val allowed = ImageSourcePolicy.allowedSource(src, servers, context!!.packageName)
		if (allowed == null) Timber.d("ImageProvider: not a server image, showing the placeholder")

		val (read, write) = ParcelFileDescriptor.createPipe()
		val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(write)

		imageLoader.enqueue(ImageRequest.Builder(context!!).apply {
			data(allowed?.toUri() ?: R.drawable.placeholder_icon)
			error(R.drawable.placeholder_icon)
			target(
				onSuccess = { image -> writeDrawable(image.asDrawable(context!!.resources), outputStream) },
				onError = { image -> writeDrawable(requireNotNull(image?.asDrawable(context!!.resources)), outputStream) }
			)
		}.build())

		return read
	}

	private fun writeDrawable(
		drawable: Drawable,
		outputStream: ParcelFileDescriptor.AutoCloseOutputStream
	) {
		@Suppress("DEPRECATION")
		val format = when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
			else -> Bitmap.CompressFormat.WEBP
		}

		try {
			outputStream.use {
				drawable.toBitmap().compress(format, COMPRESSION_QUALITY, outputStream)
			}
		} catch (_: IOException) {
			// Ignore IOException as this is commonly thrown when the load request is cancelled
		}
	}

	companion object {
		private const val COMPRESSION_QUALITY = 95

		/**
		 * Get a [Uri] that uses the [ImageProvider] to load an image. The input should be a valid
		 * Jellyfin image URL created using the SDK.
		 */
		fun getImageUri(src: String): Uri = Uri.Builder()
			.scheme("content")
			.authority("${BuildConfig.APPLICATION_ID}.integration.provider.ImageProvider")
			.appendQueryParameter("src", src)
			.appendQueryParameter("v", BuildConfig.VERSION_NAME)
			.build()
	}
}
