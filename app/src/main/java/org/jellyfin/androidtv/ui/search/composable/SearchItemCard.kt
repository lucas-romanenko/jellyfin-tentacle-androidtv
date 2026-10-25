package org.jellyfin.androidtv.ui.search.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesPrimaryImage
import org.jellyfin.androidtv.util.sdk.hasTentacleLabel
import org.jellyfin.androidtv.util.sdk.tentacleLabel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * A Jellyfin item on the Search tab: a Live TV channel (landscape, logo fitted) or a library item
 * (poster for movies and series, landscape for episodes, recordings and videos). OK opens it the
 * same way the other Search cards do: on key-up, so the submit key that moved focus here doesn't
 * click it.
 */
@Composable
fun SearchItemCard(
	item: BaseItemDto,
	api: ApiClient,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var isFocused by remember { mutableStateOf(false) }
	val isChannel = item.type == BaseItemKind.TV_CHANNEL
	val isPoster = item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES
	val shape = RoundedCornerShape(8.dp)

	Column(
		modifier = modifier
			.width(if (isPoster) 150.dp else 220.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable()
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
					onClick()
					true
				} else {
					false
				}
			}
			.clickable { onClick() }
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(if (isPoster) 2f / 3f else 16f / 9f)
				.clip(shape)
				.background(Color(0xFF1a1a2e))
				.then(if (isFocused) Modifier.border(3.dp, Color.White, shape) else Modifier),
			contentAlignment = Alignment.Center,
		) {
			val imageUrl = searchCardImageUrl(item, api, isPoster)
			if (imageUrl != null) {
				AsyncImage(
					model = imageUrl,
					contentDescription = item.name,
					contentScale = if (isChannel) ContentScale.Fit else ContentScale.Crop,
					modifier = if (isChannel) Modifier.fillMaxSize().padding(16.dp) else Modifier.fillMaxSize(),
				)
			} else {
				Icon(
					imageVector = ImageVector.vectorResource(if (isChannel) R.drawable.ic_tv else R.drawable.ic_movie),
					contentDescription = null,
					modifier = Modifier.size(48.dp),
					tint = Color.White.copy(alpha = 0.25f),
				)
			}
		}

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = searchCardTitle(item),
			fontSize = 13.sp,
			fontWeight = FontWeight.Medium,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.8f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		val subtitle = searchCardSubtitle(item)
		if (subtitle.isNotEmpty()) {
			Text(
				text = subtitle,
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private fun searchCardImageUrl(item: BaseItemDto, api: ApiClient, isPoster: Boolean): String? {
	val own = item.itemImages
	val parent = item.parentImages
	val image = if (isPoster || item.type == BaseItemKind.TV_CHANNEL) {
		own[ImageType.PRIMARY] ?: own[ImageType.THUMB]
	} else {
		own[ImageType.PRIMARY] ?: own[ImageType.THUMB] ?: parent[ImageType.THUMB] ?: item.seriesPrimaryImage ?: parent[ImageType.PRIMARY]
	}
	return image?.getUrl(api, maxHeight = 360)
}

internal fun searchCardTitle(item: BaseItemDto): String = when {
	item.type == BaseItemKind.EPISODE && !item.seriesName.isNullOrBlank() -> item.seriesName.orEmpty()
	else -> item.name.orEmpty()
}

internal fun searchCardSubtitle(item: BaseItemDto): String = if (item.hasTentacleLabel) item.tentacleLabel.orEmpty() else when (item.type) {
	BaseItemKind.TV_CHANNEL -> item.number.orEmpty()
	BaseItemKind.EPISODE -> listOfNotNull(
		item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S${s}E${e}" } },
		item.name?.takeIf { it.isNotBlank() && it != item.seriesName },
	).joinToString(" · ")
	BaseItemKind.MOVIE, BaseItemKind.SERIES -> item.productionYear?.toString().orEmpty()
	else -> ""
}
