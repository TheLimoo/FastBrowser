package com.fastbrowser.gologin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.model.UserAgentPreset
import com.fastbrowser.gologin.ui.theme.Emerald

/**
 * Shared profile create/edit dialog — used by the home Profiles list and
 * the "+ Add profile" FAB. (v1.0.6: the in-browser Profiles sheet is gone;
 * profile switching lives in the Profiles tab.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditDialog(
    profile: BrowserProfile,
    servers: List<ServerNode>,
    onDismiss: () -> Unit,
    onSave: (BrowserProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var assignedServerId by remember { mutableStateOf(profile.assignedServerId) }
    var uaPreset by remember { mutableStateOf(profile.userAgentPreset) }
    var customUa by remember { mutableStateOf(profile.customUserAgent) }
    var blockWebRtc by remember { mutableStateOf(profile.blockWebRtcLeaks) }
    var canvasNoise by remember { mutableStateOf(profile.canvasNoiseEnabled) }
    var clearOnExit by remember { mutableStateOf(profile.clearCookiesOnExit) }
    var serverExpanded by remember { mutableStateOf(false) }
    var uaExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile.name.startsWith("Profile ")) "New Profile" else "Edit Profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                ExposedDropdownMenuBox(expanded = serverExpanded, onExpandedChange = { serverExpanded = it }) {
                    OutlinedTextField(
                        value = servers.firstOrNull { it.id == assignedServerId }?.name ?: "Use active server",
                        onValueChange = {}, readOnly = true,
                        label = { Text("Bound server (optional)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) {
                        DropdownMenuItem(text = { Text("Use active server") }, onClick = { assignedServerId = null; serverExpanded = false })
                        servers.forEach { server ->
                            DropdownMenuItem(
                                text = { Text("${flagFor(server.countryCode)} ${server.name}") },
                                onClick = { assignedServerId = server.id; serverExpanded = false }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = uaExpanded, onExpandedChange = { uaExpanded = it }) {
                    OutlinedTextField(
                        value = uaPreset.label,
                        onValueChange = {}, readOnly = true,
                        label = { Text("User-Agent preset") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = uaExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = uaExpanded, onDismissRequest = { uaExpanded = false }) {
                        UserAgentPreset.entries.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = { uaPreset = preset; uaExpanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = customUa, onValueChange = { customUa = it },
                    label = { Text("Custom User-Agent (optional)") },
                    placeholder = { Text("Overrides the preset when set") },
                    modifier = Modifier.fillMaxWidth()
                )

                ProfileToggleRow("WebRTC shield", "Block WebRTC IP leaks for this profile", blockWebRtc) { blockWebRtc = it }
                ProfileToggleRow("Canvas noise", "Add slight noise to canvas fingerprints", canvasNoise) { canvasNoise = it }
                ProfileToggleRow("Clear cookies on exit", "Wipe this profile when the app closes", clearOnExit) { clearOnExit = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        profile.copy(
                            name = name.trim().ifBlank { profile.name },
                            assignedServerId = assignedServerId,
                            userAgentPreset = uaPreset,
                            customUserAgent = customUa.trim(),
                            blockWebRtcLeaks = blockWebRtc,
                            canvasNoiseEnabled = canvasNoise,
                            clearCookiesOnExit = clearOnExit
                        )
                    )
                },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ProfileToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
