package org.jellyfin.androidtv.ui.settings.screen.authentication

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.startup.PinEntryDialog
import org.jellyfin.androidtv.util.PinCodeUtil
import org.koin.compose.koinInject

@Composable
fun SettingsAuthenticationPinCodeScreen() {
	val context = LocalContext.current
	val userRepository = koinInject<UserRepository>()
	
	// Get user-specific preferences
	val userId = userRepository.currentUser.value?.id
	val userSettingPreferences = remember(userId) {
		UserSettingPreferences(context, userId)
	}
	
	var pinEnabled by remember { mutableStateOf(userSettingPreferences[UserSettingPreferences.userPinEnabled]) }
	var hasPinSet by remember { mutableStateOf(userSettingPreferences[UserSettingPreferences.userPinHash].isNotEmpty()) }
	
	// Trigger recomposition after dialogs
	var refreshTrigger by remember { mutableStateOf(0) }
	
	// Re-read state on refresh
	if (refreshTrigger > 0) {
		pinEnabled = userSettingPreferences[UserSettingPreferences.userPinEnabled]
		hasPinSet = userSettingPreferences[UserSettingPreferences.userPinHash].isNotEmpty()
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_login).uppercase()) },
				headingContent = { Text(stringResource(R.string.lbl_pin_code)) },
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.lbl_pin_code_enabled)) },
				captionContent = { Text(stringResource(R.string.lbl_pin_code_enabled_description)) },
				trailingContent = { Checkbox(checked = pinEnabled) },
				enabled = hasPinSet,
				onClick = {
					pinEnabled = !pinEnabled
					userSettingPreferences[UserSettingPreferences.userPinEnabled] = pinEnabled
				}
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.lbl_set_pin_code)) },
				captionContent = { Text(stringResource(R.string.lbl_set_pin_code_description)) },
				enabled = !hasPinSet,
				onClick = {
					PinEntryDialog.show(
						context = context,
						mode = PinEntryDialog.Mode.SET,
						onComplete = { pin ->
							if (pin != null) {
								val hash = PinCodeUtil.hashPin(pin)
								userSettingPreferences[UserSettingPreferences.userPinHash] = hash
								userSettingPreferences[UserSettingPreferences.userPinEnabled] = true
								Toast.makeText(context, R.string.lbl_pin_code_set, Toast.LENGTH_SHORT).show()
								refreshTrigger++
							}
						}
					)
				}
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.lbl_change_pin_code)) },
				captionContent = { Text(stringResource(R.string.lbl_change_pin_code_description)) },
				enabled = hasPinSet,
				onClick = {
					PinEntryDialog.show(
						context = context,
						mode = PinEntryDialog.Mode.VERIFY,
						onComplete = { oldPin ->
							if (oldPin != null) {
								val currentHash = userSettingPreferences[UserSettingPreferences.userPinHash]
								if (PinCodeUtil.hashPin(oldPin) == currentHash) {
									PinEntryDialog.show(
										context = context,
										mode = PinEntryDialog.Mode.SET,
										onComplete = { newPin ->
											if (newPin != null) {
												val hash = PinCodeUtil.hashPin(newPin)
												userSettingPreferences[UserSettingPreferences.userPinHash] = hash
												Toast.makeText(context, R.string.lbl_pin_code_changed, Toast.LENGTH_SHORT).show()
												refreshTrigger++
											}
										}
									)
								} else {
									Toast.makeText(context, R.string.lbl_pin_code_incorrect, Toast.LENGTH_SHORT).show()
								}
							}
						}
					)
				}
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.lbl_remove_pin_code)) },
				captionContent = { Text(stringResource(R.string.lbl_remove_pin_code_description)) },
				enabled = hasPinSet,
				onClick = {
					PinEntryDialog.show(
						context = context,
						mode = PinEntryDialog.Mode.VERIFY,
						onComplete = { pin ->
							if (pin != null) {
								val currentHash = userSettingPreferences[UserSettingPreferences.userPinHash]
								if (PinCodeUtil.hashPin(pin) == currentHash) {
									userSettingPreferences[UserSettingPreferences.userPinHash] = ""
									userSettingPreferences[UserSettingPreferences.userPinEnabled] = false
									Toast.makeText(context, R.string.lbl_pin_code_removed, Toast.LENGTH_SHORT).show()
									refreshTrigger++
								} else {
									Toast.makeText(context, R.string.lbl_pin_code_incorrect, Toast.LENGTH_SHORT).show()
								}
							}
						}
					)
				}
			)
		}
	}
}
