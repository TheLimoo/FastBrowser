package com.fastbrowser.gologin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.fastbrowser.gologin.ui.theme.SurfaceActive
import com.fastbrowser.gologin.ui.theme.WarningAmber

/**
 * Proxies tab — "FastBrowser proxies": our own servers as cards. There is no
 * GB meter and no Buy button — the servers are ours, traffic is unlimited.
 * Tap a card to ping it; "Set active" switches the live connection.
 */
@Composable
fun ProxiesScreen(viewModel: BrowserViewModel) {
    val servers by viewModel.servers.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val pinging by viewModel.pinging.collectAsState()

    var editingServer by remember { mutableStateOf<ServerNode?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ServerNode?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Text("FastBrowser proxies", style = MaterialTheme.typography.titleLarge)
        Text(
            "Unlimited — your own servers. Nothing to buy, no data cap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        // v1.0.5: weight(1f) pins Add Server / Ping All to the bottom while
        // the list scrolls; 96dp content padding keeps the last card clear
        // of the FAB and the bottom nav bar.
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(servers, key = { it.id }) { server ->
                val isActive = server.id == settings.activeServerId
                val result = latency[server.id]
                val rtt = result?.rttMs
                Card(
                    onClick = { viewModel.pingServer(server) },
                    colors = CardDefaults.cardColors(
                        // v1.0.5: active card gets a faint green tint.
                        containerColor = if (isActive) SurfaceActive
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isActive) Emerald else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            }
                            Text(
                                text = when {
                                    pinging.contains(server.id) -> "pinging…"
                                    rtt == null -> "offline"
                                    // v1.0.5: stale keeps the last-known
                                    // number in amber instead of "offline".
                                    result.stale -> "$rtt ms (stale)"
                                    else -> "$rtt ms"
                                },
                                color = if (result?.stale == true) WarningAmber else latencyColor(rtt),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "${server.protocol} • Unlimited",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (!isActive) {
                                Button(
                                    onClick = { viewModel.selectServer(server) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
                                ) { Text("Set active") }
                            }
                            IconButton(onClick = { editingServer = server }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit server")
                            }
                            if (servers.size > 1) {
                                IconButton(onClick = { confirmDelete = server }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete server", tint = DangerRed)
                                }
                            }
                        }
                        if (server.username.isBlank() && server.password.isBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No password — test mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (server.password.isBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "No password set — tap Edit and enter the proxy password to browse through this server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = DangerRed
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add Server")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { viewModel.pingAll() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Ping All") }
    }

    if (showAddDialog || editingServer != null) {
        ServerEditDialog(
            server = editingServer ?: viewModel.newServerTemplate(),
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
            text = { Text("The server will be removed from this device.") },
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
