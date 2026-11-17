package com.fastbrowser.gologin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fastbrowser.gologin.model.BrowserProfile
import com.fastbrowser.gologin.ui.theme.Emerald

/**
 * Home — GoLogin-style Profiles list. Each card shows the profile name, the
 * country flag + code of its bound server, the server name, a green dot while
 * its browser is running, and a round play button that launches the browser
 * with that profile. Tapping the card opens the profile editor.
 * (Orbita is GoLogin's own Chromium fork and cannot be bundled — this copies
 * its UX shell only, on top of our WebView engine.)
 */
@Composable
fun HomeScreen(
    viewModel: BrowserViewModel,
    onLaunchProfile: (BrowserProfile) -> Unit,
    onEditProfile: (BrowserProfile) -> Unit
) {
    val profiles by viewModel.profiles.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val latency by viewModel.latency.collectAsState()

    val runningProfileIds = tabs.map { it.profileId }.toSet()
    val activeServer = servers.firstOrNull { it.id == settings.activeServerId }
    val anyOnline = latency.values.any { it.isOnline }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Emerald),
                contentAlignment = Alignment.Center
            ) {
                Text("F", style = MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("FastBrowser", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Self-hosted • ${servers.size} servers • Unlimited",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (anyOnline) "● Connected" else "● Offline",
                style = MaterialTheme.typography.labelMedium,
                color = if (anyOnline) Emerald else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Active server: ${activeServer?.let { "${flagFor(it.countryCode)} ${it.name}" } ?: "Direct"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text("Profiles", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(profiles, key = { it.id }) { profile ->
                val boundServer = servers.firstOrNull { it.id == profile.assignedServerId }
                    ?: activeServer
                val isRunning = runningProfileIds.contains(profile.id)
                val isActive = profile.id == settings.activeProfileId
                Card(
                    onClick = { onEditProfile(profile) },
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
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isRunning) Emerald else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    if (boundServer != null) {
                                        append("${flagFor(boundServer.countryCode)} ${boundServer.countryCode} • ${boundServer.name}")
                                    } else {
                                        append("Uses active server")
                                    }
                                    if (isRunning) append(" • running")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                buildString {
                                    append(profile.userAgentPreset.label)
                                    if (profile.blockWebRtcLeaks) append(" • WebRTC shield")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onLaunchProfile(profile) },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Launch ${profile.name}",
                                tint = Emerald,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap ▶ to open a profile's browser. Tap the card to edit it. " +
                "Each profile keeps its own cookies, storage and cache.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
