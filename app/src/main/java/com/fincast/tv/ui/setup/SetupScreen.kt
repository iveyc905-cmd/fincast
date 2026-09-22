package com.fincast.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fincast.tv.Graph
import com.fincast.tv.data.model.SourceKind
import com.fincast.tv.sync.SyncScheduler
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.theme.Scrim
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AddMode { XTREAM, M3U }

/**
 * Playlist management and preferences.
 *
 * This is the one screen where text entry is unavoidable, so it uses ordinary
 * focus traversal and standard text fields — the TV IME handles the rest.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val playlists by Graph.playlists.observePlaylists().collectAsStateWithLifecycle(emptyList())
    val settings by Graph.settings.settings.collectAsStateWithLifecycle(
        initialValue = com.fincast.tv.data.repo.AppSettings()
    )

    var mode by remember { mutableStateOf(AddMode.XTREAM) }
    var name by remember { mutableStateOf("") }
    var portal by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun addPlaylist() {
        busy = true
        message = null
        scope.launch {
            val result = runCatching {
                when (mode) {
                    AddMode.XTREAM -> Graph.playlists.addXtream(
                        name = name,
                        portalUrl = portal,
                        username = username,
                        password = password,
                        userAgent = settings.userAgent,
                    )
                    AddMode.M3U -> Graph.playlists.addM3uUrl(
                        name = name,
                        url = m3uUrl,
                        userAgent = settings.userAgent,
                    )
                }
            }
            busy = false
            result.fold(
                onSuccess = {
                    message = "Playlist added"
                    name = ""; portal = ""; username = ""; password = ""; m3uUrl = ""
                    SyncScheduler.refreshNow(context)
                },
                onFailure = { message = it.message ?: "Could not add the playlist" },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Playlists", style = MaterialTheme.typography.titleLarge)
        VSpace(20)

        playlists.forEach { playlist ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Scrim.row)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = buildString {
                            append(
                                when (playlist.kind) {
                                    SourceKind.XTREAM -> "Xtream"
                                    SourceKind.M3U_URL -> "M3U"
                                    SourceKind.M3U_FILE -> "File"
                                }
                            )
                            append("  ·  ")
                            append(
                                if (playlist.lastSyncMs == 0L) "never synced"
                                else "synced " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                                    .format(Date(playlist.lastSyncMs))
                            )
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        val result = Graph.playlists.refresh(playlist.id)
                        busy = false
                        message = result.fold(
                            onSuccess = { "${playlist.name}: $it channels" },
                            onFailure = { it.message },
                        )
                    }
                }) { Text("Refresh") }
                TextButton(onClick = {
                    scope.launch { Graph.playlists.delete(playlist.id) }
                }) { Text("Remove") }
            }
            VSpace(8)
        }

        VSpace(24)
        Text("Add a playlist", style = MaterialTheme.typography.titleMedium)
        VSpace(12)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(
                selected = mode == AddMode.XTREAM,
                onClick = { mode = AddMode.XTREAM },
                label = { Text("Xtream Codes login") },
            )
            FilterChip(
                selected = mode == AddMode.M3U,
                onClick = { mode = AddMode.M3U },
                label = { Text("M3U URL") },
            )
        }
        VSpace(16)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.width(640.dp),
        )
        VSpace(12)

        when (mode) {
            AddMode.XTREAM -> {
                OutlinedTextField(
                    value = portal,
                    onValueChange = { portal = it },
                    label = { Text("Portal URL, e.g. http://example.com:8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.width(640.dp),
                )
                VSpace(12)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.width(640.dp),
                )
                VSpace(12)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.width(640.dp),
                )
            }

            AddMode.M3U -> {
                OutlinedTextField(
                    value = m3uUrl,
                    onValueChange = { m3uUrl = it },
                    label = { Text("Playlist URL (http/https)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.width(640.dp),
                )
            }
        }

        VSpace(16)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { addPlaylist() },
                enabled = !busy && when (mode) {
                    AddMode.XTREAM -> portal.isNotBlank() && username.isNotBlank()
                    AddMode.M3U -> m3uUrl.isNotBlank()
                },
            ) { Text("Add and load") }

            if (busy) {
                Box(Modifier.padding(start = 16.dp)) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                }
            }
            message?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        VSpace(32)
        Text("Preferences", style = MaterialTheme.typography.titleMedium)
        VSpace(12)

        SettingSwitch(
            label = "Resume last channel on start",
            checked = settings.resumeOnStart,
            onChange = { scope.launch { Graph.settings.setResumeOnStart(it) } },
        )
        SettingSwitch(
            label = "Show channel numbers",
            checked = settings.showChannelNumbers,
            onChange = { scope.launch { Graph.settings.setShowChannelNumbers(it) } },
        )
        SettingSwitch(
            label = "Tunneled video decoding (turn off if the picture is black)",
            checked = settings.tunnelingEnabled,
            onChange = { scope.launch { Graph.settings.setTunneling(it) } },
        )

        VSpace(12)
        // Edited locally and written on commit — round-tripping every keystroke
        // through DataStore makes the field fight the cursor.
        var userAgentDraft by remember(settings.userAgent) { mutableStateOf(settings.userAgent) }
        OutlinedTextField(
            value = userAgentDraft,
            onValueChange = { userAgentDraft = it },
            label = { Text("User agent sent to the provider") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { scope.launch { Graph.settings.setUserAgent(userAgentDraft) } }
            ),
            modifier = Modifier.width(640.dp),
        )
        TextButton(
            onClick = { scope.launch { Graph.settings.setUserAgent(userAgentDraft) } },
            enabled = userAgentDraft != settings.userAgent,
        ) { Text("Save user agent") }

        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(10, 30, 60).forEach { seconds ->
                FilterChip(
                    selected = settings.bufferSeconds == seconds,
                    onClick = { scope.launch { Graph.settings.setBufferSeconds(seconds) } },
                    label = { Text("${seconds}s buffer") },
                )
            }
        }

        VSpace(28)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { SyncScheduler.refreshNow(context) }) {
                Text("Refresh everything now")
            }
            Button(onClick = onDone, enabled = playlists.isNotEmpty()) {
                Text("Start watching")
            }
        }
        VSpace(24)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .width(760.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
