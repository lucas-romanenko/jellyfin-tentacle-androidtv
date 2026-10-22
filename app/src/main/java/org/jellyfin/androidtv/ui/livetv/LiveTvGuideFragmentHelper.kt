package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.text.NumberFormat
import java.time.Instant
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

fun LiveTvGuideFragment.toggleFavorite() {
	val header = mSelectedProgramView as? GuideChannelHeader
	val channel = header?.channel ?: return

	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = header.channel.id,
				favorite = !(channel.userData?.isFavorite ?: false)
			)

			val updated = header.channel.copy(userData = userData)
			header.channel = updated
			header.findViewById<View>(R.id.favImage).isVisible = userData.isFavorite
			// The stored copy too: the programme popup's heart and the Favorites count read it
			TvManager.updateChannel(updated)
			onFavoritesChanged()
			dataRefreshService.lastFavoriteUpdate = Instant.now()
		}
	}
}

/**
 * Recordings / Schedule / Series from the guide's header: the guide is where Live TV opens now,
 * so these stay one press away. Schedule and Series only for people who may manage recordings.
 */
fun LiveTvGuideFragment.setupGuideLinks(binding: LiveTvGuideBinding) {
	val navigationRepository by inject<NavigationRepository>()
	val userRepository by inject<UserRepository>()
	val canManage = userRepository.currentUser.value?.policy?.enableLiveTvManagement == true

	binding.recordingsButton.setOnClickListener { navigationRepository.navigate(Destinations.liveTvRecordings) }
	binding.scheduleButton.isVisible = canManage
	binding.scheduleButton.setOnClickListener { navigationRepository.navigate(Destinations.liveTvSchedule) }
	binding.seriesButton.isVisible = canManage
	binding.seriesButton.setOnClickListener { navigationRepository.navigate(Destinations.liveTvSeriesRecordings) }
	// Icons only, so the header keeps room for every day; the focused one shows its name
	for ((button, label) in listOf(
		binding.recordingsButton to R.string.lbl_recordings,
		binding.scheduleButton to R.string.lbl_schedule,
		binding.seriesButton to R.string.lbl_series,
	)) {
		button.compoundDrawablePadding = 0
		button.setOnFocusChangeListener { _, focused ->
			button.text = if (focused) getString(label) else null
			button.compoundDrawablePadding = if (focused) button.resources.getDimensionPixelSize(R.dimen.guide_chip_icon_gap) else 0
		}
	}
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
			Timber.e(error, "Unable to get program details")
		}

		if (mSelectedProgram?.id == id) detailUpdateInternal()
	}
}

fun LiveTvGuideFragment.addSettingsOptions(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsOptions.setContent {
		val isVisible by visible.collectAsState(false)

		JellyfinTheme {
			ProvideRouter(
				routes,
				Routes.LIVETV_GUIDE_OPTIONS
			) {
				SettingsDialog(
					visible = isVisible,
					onDismissRequest = {
						visible.value = false
						TvManager.forceReload()
						doLoad()
					}
				) {
					SettingsRouterContent()
				}
			}
		}
	}

	return visible
}

fun LiveTvGuideFragment.addSettingsFilters(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsFilters.setContent {
		val isVisible by visible.collectAsState(false)

		JellyfinTheme {
			ProvideRouter(
				routes,
				Routes.LIVETV_GUIDE_FILTERS
			) {
				SettingsDialog(
					visible = isVisible,
					onDismissRequest = {
						visible.value = false
						TvManager.forceReload()
						doLoad()
					}
				) {
					SettingsRouterContent()
				}
			}
		}
	}

	return visible
}
