package com.fastbrowser.gologin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.ui.theme.DangerRed
import com.fastbrowser.gologin.ui.theme.Emerald
import com.fastbrowser.gologin.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSwitcherSheet(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit,
    /** When set (e.g. by the blank-password guard), opens this server's editor immediately. */
    editServer: ServerNode? = null
) {
    val servers by viewModel.servers.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val pinging by viewModel.pinging.collectAsState()

    var editingServer by remember { mutableStateOf<ServerNode?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ServerNode?>(null) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Switch Server", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { viewModel.pingAll() }) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Ping All")
                }
            }
            Text(
                "One tap swaps the live connection — the bridge hot-swaps in under 1 ms and the " +
                    "current tab reloads through the new server automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            servers.forEach { server ->
                val isActive = server.id == settings.activeServerId
                val result = latency[server.id]
                val rtt = result?.rttMs
                Card(
                    onClick = {
                        viewModel.selectServer(server)
                        onDismiss()
                    },
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
                        Text(flagFor(server.countryCode), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(server.name, style = MaterialTheme.typography.titleMedium)
                                if (isActive) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Filled.Check, contentDescription = "Active", tint = Emerald)
                                }
                            }
                            Text(
                                "${server.host}:${server.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    server.protocol.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = when {
                                    pinging.contains(server.id) -> "pinging…"
                                    rtt == null -> "offline"
                                    // v1.0.6: stale keeps the last-known number
                                    // in amber, like the other screens.
                                    result.stale -> "$rtt ms (stale)"
                                    else -> "$rtt ms"
                                },
                                color = if (result?.stale == true) WarningAmber else latencyColor(rtt),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row {
                                IconButton(onClick = { editingServer = server }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit server")
                                }
                                if (servers.size > 1) {
                                    IconButton(onClick = { confirmDelete = server }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete server", tint = DangerRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Direct connection row — gated on the kill-switch being OFF.
            Card(
                onClick = {
                    if (settings.killSwitchEnabled) {
                        viewModel.snack("Turn off Strict Kill-Switch in Settings to use Direct mode")
                    } else {
                        viewModel.selectDirectConnection()
                        onDismiss()
                    }
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Direct Connection (Bypass Proxy)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (settings.killSwitchEnabled) "Disabled while Kill-Switch is ON"
                            else "Traffic goes direct — your real IP is exposed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.killSwitchEnabled) DangerRed
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (settings.activeServerId == null) {
                        Icon(Icons.Filled.Check, contentDescription = "Active", tint = Emerald)
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Server")
            }

            if (settings.autoSelectFastestServer) {
                Text(
                    "Auto-select fastest is ON — the app picks the lowest-RTT server on launch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Emerald
                )
            }
        }
    }

    if (showAddDialog || editingServer != null || editServer != null) {
        ServerEditDialog(
            server = editingServer ?: editServer ?: viewModel.newServerTemplate(),
            onDismiss = { showAddDialog = false; editingServer = null },
            onSave = { saved ->
                viewModel.addOrUpdateServer(saved)
                showAddDialog = false
                editingServer = null
            }
        )
    }

    confirmDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${server.name}?") },
            text = { Text("The server will be removed from this device. Tabs using it keep working on the next server.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteServer(server); confirmDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
