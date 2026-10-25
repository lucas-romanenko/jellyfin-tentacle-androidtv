package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.playback.rethrowIfCancelled
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.NumberFormat
import java.time.LocalDateTime
import java.util.UUID

fun createNoProgramDataBaseItem(
	context: Context,
	channelId: UUID?,
	startDate: LocalDateTime?,
	endDate: LocalDateTime?
) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.FOLDER,
	mediaType = MediaType.UNKNOWN,
	name = context.getString(R.string.no_program_data),
	channelId = channelId,
	startDate = startDate,
	endDate = endDate,
)

/**
 * Recordings from the guide's header: the guide is where Live TV opens now, so the one Recordings
 * screen (Recorded, and Scheduled / Series for people who may manage recordings) stays one press
 * away (#203).
 */
fun LiveTvGuideFragment.setupGuideLinks(binding: LiveTvGuideBinding) {
	val navigationRepository by inject<NavigationRepository>()
	binding.recordingsButton.setOnClickListener { navigationRepository.navigate(Destinations.liveTvRecordings) }
}

/** "Favorites  3" / "All  5,669": the count a little smaller than the label. */
fun guideToggleLabel(label: String, count: Int?): CharSequence {
	if (count == null || count < 0) return label
	val number = NumberFormat.getIntegerInstance().format(count)
	return SpannableString("$label  $number").apply {
		setSpan(RelativeSizeSpan(0.85f), label.length + 2, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
	}
}

fun LiveTvGuideFragment.refreshSelectedProgram() {
	val api by inject<ApiClient>()
	val id = mSelectedProgram?.id ?: return

	lifecycleScope.launch {
		runCatching {
			val item = withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(id).content
			}
			// Focus moved on meanwhile: a slower answer for an earlier programme must not replace it
			if (mSelectedProgram?.id == id) mSelectedProgram = item
		}.onFailure { error ->
			// The guide closed while this ran: it's gone, there is nothing to redraw (#181)
			error.rethrowIfCancelled()
			Timber.e(error, "Unable to get program details")
		}

		if (mSelectedProgram?.id == id) detailUpdateInternal()
	}
}
