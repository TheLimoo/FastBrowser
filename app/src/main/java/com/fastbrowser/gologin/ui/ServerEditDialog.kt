package com.fastbrowser.gologin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.model.ProxyProtocol
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.network.LatencyMonitor
import com.fastbrowser.gologin.ui.theme.DangerRed
import com.fastbrowser.gologin.ui.theme.Emerald
import kotlinx.coroutines.launch

/**
 * Shared server add/edit dialog — used by the server switcher sheet and the
 * Proxies tab. The password field is the place users type the proxy password
 * once; it is stored only in the app's on-device DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditDialog(
    server: ServerNode,
    onDismiss: () -> Unit,
    onSave: (ServerNode) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var host by remember { mutableStateOf(server.host) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var protocol by remember { mutableStateOf(server.protocol) }
    var username by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf(server.password) }
    var timezone by remember { mutableStateOf(server.timezoneOverride) }
    var showPassword by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var protocolExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portValid = port.toIntOrNull() in 1..65535
    val hostValid = host.isNotBlank()
    val canSave = name.isNotBlank() && hostValid && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (server.host.isBlank()) "Add Server" else "Edit Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it.trim() }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = !hostValid)
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !portValid
                )
                ExposedDropdownMenuBox(
                    expanded = protocolExpanded,
                    onExpandedChange = { protocolExpanded = it }
                ) {
                    OutlinedTextField(
                        value = protocol.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = protocolExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = protocolExpanded, onDismissRequest = { protocolExpanded = false }) {
                        ProxyProtocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.name) },
                                onClick = { protocol = p; protocolExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (optional — servers currently need none)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    }
                )
                OutlinedTextField(value = timezone, onValueChange = { timezone = it }, label = { Text("Timezone override (optional)") }, placeholder = { Text("Europe/Stockholm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            // v1.0.6: probe with a copy of the edited server
                            // (was a fresh ServerNode that dropped transport
                            // + wsPath — testing the Cloudflare server then
                            // ran a plain TCP check instead of the WS
                            // handshake, and the label lied about it).
                            val probe = server.copy(
                                name = name, host = host,
                                port = port.toIntOrNull() ?: 0, protocol = protocol,
                                username = username, password = password
                            )
                            val result = LatencyMonitor.pingServer(probe)
                            testResult = if (result.isOnline) "Reachable — ${result.rttMs} ms TCP handshake"
                            else "Unreachable — check host, port and firewall"
                            testing = false
                        }
                    },
                    enabled = hostValid && portValid && !testing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (testing) "Testing…" else "Test Connection & Latency")
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Reachable")) Emerald else DangerRed) }
                Text(
                    "Password is stored only on this phone, in the app's private DataStore. It is never uploaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        server.copy(
                            name = name.trim(), host = host.trim(),
                            port = port.toIntOrNull() ?: server.port,
                            protocol = protocol, username = username.trim(),
                            password = password, timezoneOverride = timezone.trim()
                        )
                    )
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
