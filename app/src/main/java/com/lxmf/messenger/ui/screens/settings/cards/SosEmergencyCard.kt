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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
) {
    CollapsibleSettingsCard(
        title = "SOS Emergency",
        icon = Icons.Filled.Warning,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Master toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable SOS", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Allow sending emergency distress signals",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = sosEnabled, onCheckedChange = onSosEnabledChange)
            }

            if (sosEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "$sosContactCount SOS contact(s) configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sosContactCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Message template
                OutlinedTextField(
                    value = sosMessageTemplate,
                    onValueChange = onSosMessageTemplateChange,
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
}
