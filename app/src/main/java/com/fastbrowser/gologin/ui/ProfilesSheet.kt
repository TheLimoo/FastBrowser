package com.fastbrowser.gologin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.model.UserAgentPreset
import com.fastbrowser.gologin.ui.theme.DangerRed
import com.fastbrowser.gologin.ui.theme.Emerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var editingProfile by remember { mutableStateOf<BrowserProfile?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<BrowserProfile?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Profiles", style = MaterialTheme.typography.titleLarge)
            Text(
                "Each profile has its own cookies, storage and cache — log into the same site with different accounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            profiles.forEach { profile ->
                val isActive = profile.id == settings.activeProfileId
                val boundServer = servers.firstOrNull { it.id == profile.assignedServerId }
                Card(
                    onClick = { viewModel.selectProfile(profile); onDismiss() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, Emerald) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                if (isActive) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.Check, contentDescription = "Active", tint = Emerald)
                                }
                            }
                            Text(
                                buildString {
                                    append(profile.userAgentPreset.label)
                                    if (boundServer != null) append(" • bound to ${boundServer.name}")
                                    if (profile.blockWebRtcLeaks) append(" • WebRTC shield")
                                    if (profile.canvasNoiseEnabled) append(" • canvas noise")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { editingProfile = profile }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit profile")
                        }
                        IconButton(onClick = { viewModel.wipeProfileData(profile) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Wipe cookies & cache")
                        }
                        if (profiles.size > 1) {
                            IconButton(onClick = { confirmDelete = profile }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete profile", tint = DangerRed)
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New Profile")
            }
        }
    }

    if (showAddDialog || editingProfile != null) {
        ProfileEditDialog(
            profile = editingProfile ?: viewModel.newProfileTemplate(),
            servers = servers,
            onDismiss = { showAddDialog = false; editingProfile = null },
            onSave = { saved ->
                viewModel.addOrUpdateProfile(saved)
                showAddDialog = false
                editingProfile = null
            }
        )
    }

    confirmDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("Its cookies, storage and cache are wiped and its tabs are closed.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteProfile(profile); confirmDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
