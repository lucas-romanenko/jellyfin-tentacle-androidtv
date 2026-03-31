package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto

private val AppBackground = Color(0xFF0F0D1A)

@Composable
fun HeroSpotlight(
	items: List<BaseItemDto>,
	api: ApiClient,
	onItemSelected: (BaseItemDto) -> Unit,
	onScrollDown: () -> Unit,
	buttonFocusRequester: FocusRequester,
	modifier: Modifier = Modifier,
) {
	if (items.isEmpty()) return

	var currentIndex by remember { mutableIntStateOf(0) }

	LaunchedEffect(currentIndex, items.size) {
		if (items.size > 1) {
			delay(8000L)
			currentIndex = (currentIndex + 1) % items.size
		}
	}

	val currentItem = items[currentIndex]

	Box(
		modifier = modifier
			.fillMaxWidth()
			.background(AppBackground),
	) {
		// Backdrop image — fills entire hero area
		AnimatedContent(
			targetState = currentIndex,
			transitionSpec = {
				fadeIn(animationSpec = tween(800)) togetherWith fadeOut(animationSpec = tween(800))
			},
			label = "hero-backdrop",
		) { index ->
			val item = items[index]
			val backdropUrl = item.itemBackdropImages.firstOrNull()?.getUrl(api, fillWidth = 1920)

			if (backdropUrl != null) {
				AsyncImage(
					url = backdropUrl,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		// Top scrim for toolbar readability
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.fillMaxHeight(0.15f)
				.align(Alignment.TopCenter)
				.background(
					Brush.verticalGradient(
						0f to AppBackground.copy(alpha = 0.7f),
						1f to Color.Transparent,
					)
				)
		)

		// Bottom scrim for text readability
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.fillMaxHeight(0.5f)
				.align(Alignment.BottomCenter)
				.background(
					Brush.verticalGradient(
						0f to Color.Transparent,
						0.4f to AppBackground.copy(alpha = 0.6f),
						1f to AppBackground,
					)
				)
		)

		// Content at bottom-left
		Column(
			modifier = Modifier
				.align(Alignment.BottomStart)
				.padding(start = 48.dp, bottom = 48.dp, end = 48.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			// Title
			Text(
				text = currentItem.name ?: "",
				fontSize = 32.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)

			// Metadata: year, rating, genres
			val metaParts = buildList {
				currentItem.productionYear?.let { add(it.toString()) }
				currentItem.officialRating?.let { add(it) }
				currentItem.communityRating?.let { add("%.1f".format(it)) }
				currentItem.genres?.take(3)?.let { addAll(it) }
			}
			if (metaParts.isNotEmpty()) {
				Text(
					text = metaParts.joinToString(" \u2022 "),
					fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.7f),
					maxLines = 1,
				)
			}

			// Overview — 2 lines, only covers left half so image shows on right
			currentItem.overview?.let { overview ->
				Text(
					text = overview,
					fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.6f),
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.fillMaxWidth(0.5f),
				)
			}

			Spacer(Modifier.height(6.dp))

			// Button + dot indicators
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(16.dp),
			) {
				// D-pad Left/Right cycles hero content, Down collapses hero
				Button(
					onClick = { onItemSelected(currentItem) },
					modifier = Modifier
						.focusRequester(buttonFocusRequester)
						.onPreviewKeyEvent { event ->
							if (event.type == KeyEventType.KeyDown) {
								when (event.key) {
									Key.DirectionLeft -> {
										if (items.size > 1) {
											currentIndex = if (currentIndex > 0) currentIndex - 1 else items.size - 1
										}
										true
									}
									Key.DirectionRight -> {
										if (items.size > 1) {
											currentIndex = (currentIndex + 1) % items.size
										}
										true
									}
									Key.DirectionDown -> {
										onScrollDown()
										true
									}
									else -> false
								}
							} else false
						},
				) {
					Text("More Info")
				}

				if (items.size > 1) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(5.dp),
					) {
						items.forEachIndexed { index, _ ->
							Box(
								modifier = Modifier
									.width(if (index == currentIndex) 18.dp else 6.dp)
									.height(6.dp)
									.background(
										if (index == currentIndex) Color.White
										else Color.White.copy(alpha = 0.3f),
										shape = JellyfinTheme.shapes.small,
									)
							)
						}
					}
				}
			}
		}
	}
}
