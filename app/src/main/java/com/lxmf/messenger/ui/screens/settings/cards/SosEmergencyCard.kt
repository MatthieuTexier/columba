package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.SosTriggerMode
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

@Composable
fun SosEmergencyCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    sosEnabled: Boolean,
    onSosEnabledChange: (Boolean) -> Unit,
    sosMessageTemplate: String,
    onSosMessageTemplateChange: (String) -> Unit,
    sosCountdownSeconds: Int,
    onSosCountdownSecondsChange: (Int) -> Unit,
    sosIncludeLocation: Boolean,
    onSosIncludeLocationChange: (Boolean) -> Unit,
    sosSilentAutoAnswer: Boolean,
    onSosSilentAutoAnswerChange: (Boolean) -> Unit,
    sosShowFloatingButton: Boolean,
    onSosShowFloatingButtonChange: (Boolean) -> Unit,
    sosDeactivationPin: String?,
    onSosDeactivationPinChange: (String?) -> Unit,
    sosPeriodicUpdates: Boolean,
    onSosPeriodicUpdatesChange: (Boolean) -> Unit,
    sosUpdateIntervalSeconds: Int,
    onSosUpdateIntervalSecondsChange: (Int) -> Unit,
    sosContactCount: Int,
    sosTriggerModes: Set<String>,
    onSosTriggerModeToggle: (String) -> Unit,
    sosShakeSensitivity: Float,
    onSosShakeSensitivityChange: (Float) -> Unit,
    sosTapCount: Int,
    onSosTapCountChange: (Int) -> Unit,
) {
    CollapsibleSettingsCard(
        title = "SOS Emergency",
        icon = Icons.Filled.Warning,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            Switch(checked = sosEnabled, onCheckedChange = onSosEnabledChange)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                    "$sosContactCount SOS contact(s) configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sosContactCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Message template (local state to avoid cursor reset on async DataStore roundtrip)
                var templateText by remember { mutableStateOf(sosMessageTemplate) }
                OutlinedTextField(
                    value = templateText,
                    onValueChange = {
                        templateText = it
                        onSosMessageTemplateChange(it)
                    },
                    label = { Text("Message Template") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Countdown slider
                Text(
                    "Countdown: ${sosCountdownSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (sosCountdownSeconds == 0) "SOS will send instantly" else "Delay before sending",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = sosCountdownSeconds.toFloat(),
                    onValueChange = { onSosCountdownSecondsChange(it.toInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Include location
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include GPS Location", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Append coordinates to SOS message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sosIncludeLocation, onCheckedChange = onSosIncludeLocationChange)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Silent auto-answer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Silent Auto-Answer", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Auto-answer incoming calls during active SOS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sosSilentAutoAnswer, onCheckedChange = onSosSilentAutoAnswerChange)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Floating button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Floating SOS Button", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Show a floating SOS trigger button",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sosShowFloatingButton, onCheckedChange = onSosShowFloatingButtonChange)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Trigger modes (multi-select)
                Text("Trigger Modes", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "How SOS can be activated (in addition to the button)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val activeModes = SosTriggerMode.fromKeys(sosTriggerModes)
                SosTriggerMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = mode in activeModes,
                            onCheckedChange = { onSosTriggerModeToggle(mode.key) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                when (mode) {
                                    SosTriggerMode.SHAKE -> "Shake phone"
                                    SosTriggerMode.TAP_PATTERN -> "Tap pattern"
                                    SosTriggerMode.POWER_BUTTON -> "Power button"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                when (mode) {
                                    SosTriggerMode.SHAKE -> "Shake the device vigorously to trigger"
                                    SosTriggerMode.TAP_PATTERN -> "Tap the back of the phone rapidly"
                                    SosTriggerMode.POWER_BUTTON -> "Press power button 3 times rapidly"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (SosTriggerMode.SHAKE in activeModes) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Shake sensitivity: ${"%.1f".format(sosShakeSensitivity)}x",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Lower = more sensitive, Higher = harder shake required",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = sosShakeSensitivity,
                        onValueChange = onSosShakeSensitivityChange,
                        valueRange = 1.0f..5.0f,
                        steps = 7,
                    )
                }

                if (SosTriggerMode.TAP_PATTERN in activeModes) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Required taps: $sosTapCount",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Number of rapid taps needed to trigger SOS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = sosTapCount.toFloat(),
                        onValueChange = { onSosTapCountChange(it.toInt()) },
                        valueRange = 3f..5f,
                        steps = 1,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Periodic updates
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Periodic Location Updates", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Send location updates while SOS is active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = sosPeriodicUpdates, onCheckedChange = onSosPeriodicUpdatesChange)
                }

                if (sosPeriodicUpdates) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Update interval: ${sosUpdateIntervalSeconds}s",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = sosUpdateIntervalSeconds.toFloat(),
                        onValueChange = { onSosUpdateIntervalSecondsChange(it.toInt()) },
                        valueRange = 30f..600f,
                        steps = 56,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Deactivation PIN
                var pinText by remember { mutableStateOf(sosDeactivationPin ?: "") }
                OutlinedTextField(
                    value = pinText,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isDigit() }.take(6)
                        pinText = filtered
                        onSosDeactivationPinChange(filtered.ifBlank { null })
                    },
                    label = { Text("Deactivation PIN (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("4-6 digit PIN required to deactivate SOS") },
                    singleLine = true,
                )
        }
    }
}
