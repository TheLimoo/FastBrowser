package com.fastbrowser.gologin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.model.ServerNode
import com.fastbrowser.gologin.network.LatencyMonitor
import com.fastbrowser.gologin.ui.theme.Emerald
import kotlinx.coroutines.launch

/**
 * First-launch Quick Connect: both pre-seeded servers with empty password fields,
 * a per-server ping test, then Launch Browser. Passwords are typed once here and
 * stored only in the app's DataStore.
 */
@Composable
fun OnboardingScreen(viewModel: BrowserViewModel) {
    val servers by viewModel.servers.collectAsState()
    val scope = rememberCoroutineScope()

    val passwords = remember { mutableStateMapOf<String, String>() }
    val showPassword = remember { mutableStateMapOf<String, Boolean>() }
    val pingResults = remember { mutableStateMapOf<String, String>() }
    val pinging = remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("FastBrowser", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your traffic routes through your own servers. Enter each server's proxy password once — " +
                "it stays on this phone only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        servers.forEach { server ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(flagFor(server.countryCode), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        Text(server.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${server.protocol}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${server.host}:${server.port} • user ${server.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = passwords[server.id] ?: server.password,
                        onValueChange = { passwords[server.id] = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword[server.id] == true) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { showPassword[server.id] = showPassword[server.id] != true }) {
                                Text(if (showPassword[server.id] == true) "Hide" else "Show")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = {
                                pinging.value = true
                                pingResults.remove(server.id)
                                scope.launch {
                                    val probe: ServerNode = server.copy(
                                        password = passwords[server.id] ?: server.password
                                    )
                                    val result = LatencyMonitor.pingServer(probe)
                                    pingResults[server.id] = if (result.isOnline) "Reachable — ${result.rttMs} ms" else "Unreachable"
                                    pinging.value = false
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Speed, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Test Ping")
                        }
                        Spacer(Modifier.width(8.dp))
                        pingResults[server.id]?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Reachable")) Emerald else Color.Red)
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                saving = true
                servers.forEach { server ->
                    val pw = passwords[server.id]
                    if (pw != null && pw != server.password) {
                        viewModel.addOrUpdateServer(server.copy(password = pw))
                    }
                }
                viewModel.setOnboardingDone()
            },
            enabled = !saving,
            colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (saving) "Starting…" else "Launch Browser")
        }
        Text(
            "You can change passwords later in Settings → Manage servers. The kill-switch is ON by default: " +
                "if a server is unreachable, nothing loads instead of leaking your real IP.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
