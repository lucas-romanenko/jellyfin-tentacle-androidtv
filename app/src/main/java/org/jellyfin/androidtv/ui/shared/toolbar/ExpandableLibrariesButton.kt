package org.jellyfin.androidtv.ui.shared.toolbar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.Session
import org.jellyfin.androidtv.data.model.AggregatedLibrary
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonColors
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/**
 * A single button that shows a Libraries icon and expands to show all available libraries when focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExpandableLibrariesButton(
	activeLibraryId: UUID?,
	userViews: List<BaseItemDto>,
	aggregatedLibraries: List<AggregatedLibrary>,
	enableMultiServer: Boolean,
	currentSession: Session?,
	colors: ButtonColors,
	activeColors: ButtonColors,
	navigationRepository: NavigationRepository,
	itemLauncher: ItemLauncher,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()
	val scope = rememberCoroutineScope()
	val bringIntoViewRequester = remember { BringIntoViewRequester() }
	
	var isExpanded by remember { mutableStateOf(false) }
	val hasActiveLibrary = activeLibraryId != null

	LaunchedEffect(isFocused) {
		if (isFocused) {
			scope.launch {
				bringIntoViewRequester.bringIntoView()
			}
		}
	}

	val contentPadding = if (isFocused) {
		PaddingValues(horizontal = 16.dp, vertical = 10.dp)
	} else {
		PaddingValues(horizontal = 5.dp, vertical = 10.dp)
	}

	Box(
		modifier = Modifier.onFocusChanged { focusState ->
			if (!focusState.hasFocus) isExpanded = false
		}
	) {
		Row(
			modifier = Modifier.focusGroup(),
			horizontalArrangement = Arrangement.spacedBy(0.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Button(
				onClick = { isExpanded = !isExpanded },
				colors = if (hasActiveLibrary) activeColors else colors,
				contentPadding = contentPadding,
				modifier = Modifier
					.then(if (!isFocused) Modifier.requiredWidthIn(max = 36.dp) else Modifier)
					.bringIntoViewRequester(bringIntoViewRequester),
				interactionSource = interactionSource,
			) {
				Row(
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_clapperboard),
						contentDescription = "Libraries",
					)

					if (isFocused) {
						Spacer(modifier = Modifier.width(8.dp))
						ProvideTextStyle(
							JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Bold)
						) {
							Text(
								text = stringResource(R.string.pref_libraries),
								modifier = Modifier.padding(end = 4.dp)
							)
						}
					}
				}
			}
			
			AnimatedVisibility(
				visible = isExpanded,
				enter = expandHorizontally(
					expandFrom = Alignment.Start,
					animationSpec = tween(durationMillis = 250)
				) + fadeIn(animationSpec = tween(durationMillis = 250)),
				exit = shrinkHorizontally(
					shrinkTowards = Alignment.Start,
					animationSpec = tween(durationMillis = 200)
				) + fadeOut(animationSpec = tween(durationMillis = 200)),
			) {
				Row(
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Spacer(modifier = Modifier.width(8.dp))
					
					ProvideTextStyle(
						JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Bold)
					) {
						if (enableMultiServer && aggregatedLibraries.isNotEmpty()) {
							aggregatedLibraries.forEach { aggLib ->
								val isActiveLibrary = activeLibraryId == aggLib.library.id
								
								Button(
									onClick = {
										if (!isActiveLibrary) {
											scope.launch {
												val destination = when (aggLib.library.collectionType) {
													CollectionType.LIVETV, CollectionType.MUSIC -> {
														itemLauncher.getUserViewDestination(aggLib.library)
													}
													else -> {
														Destinations.libraryBrowser(aggLib.library, aggLib.server.id, aggLib.userId)
													}
												}
												navigationRepository.navigate(destination)
											}
										}
									},
									colors = if (isActiveLibrary) activeColors else colors,
								) {
									Text(aggLib.displayName)
								}
							}
						} else {
							userViews.forEach { library ->
								val isActiveLibrary = activeLibraryId == library.id
								
								Button(
									onClick = {
										if (!isActiveLibrary) {
											val destination = itemLauncher.getUserViewDestination(library)
											navigationRepository.navigate(destination)
										}
									},
									colors = if (isActiveLibrary) activeColors else colors,
								) {
									Text(library.name ?: "")
								}
							}
						}
					}
					
					Spacer(modifier = Modifier.width(4.dp))
				}
			}
		}
	}
}
