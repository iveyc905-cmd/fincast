package com.fincast.tv.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.player.Playback
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.player.rememberPlayback
import com.fincast.tv.ui.setup.SetupScreen
import com.fincast.tv.ui.theme.Scrim

private enum class Tab(val label: String) { HOME("Home"), GUIDE("Guide"), SEARCH("Search"), SETTINGS("Settings") }

/**
 * The phone and tablet shell: four tabs, a mini-player that keeps the stream
 * going while browsing, and the full player page sliding over the top.
 */
@Composable
fun MobileApp(viewModel: PlayerViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback = rememberPlayback(viewModel, autoTuneFirst = false, resumeLast = false)

    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    // A category opened via "See all"; null shows the tab itself.
    var browseGroup by rememberSaveable { mutableStateOf<String?>(null) }

    val play: (ChannelWithNow) -> Unit = { row ->
        viewModel.tuneInGroup(row.channel)
        playerOpen = true
    }

    BackHandler(enabled = browseGroup != null && !playerOpen) { browseGroup = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (state.currentChannel != null && !playerOpen) {
                    MiniPlayer(state, playback, viewModel, onOpen = { playerOpen = true })
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && browseGroup == null,
                            onClick = { tab = t; browseGroup = null },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.HOME -> Icons.Filled.Home
                                        Tab.GUIDE -> Icons.Filled.LiveTv
                                        Tab.SEARCH -> Icons.Filled.Search
                                        Tab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val group = browseGroup
        when {
            group != null -> BrowsePage(
                group = group,
                state = state,
                viewModel = viewModel,
                onBack = { browseGroup = null },
                onPlay = play,
                onOpenSettings = { browseGroup = null; tab = Tab.SETTINGS },
                modifier = Modifier.padding(padding),
            )
            tab == Tab.HOME -> HomeScreen(
                state = state,
                viewModel = viewModel,
                contentPadding = padding,
                onPlay = play,
                onSearch = { tab = Tab.SEARCH },
                onBrowseGroup = { browseGroup = it ?: "" },
                onOpenSettings = { tab = Tab.SETTINGS },
            )
            tab == Tab.GUIDE -> MobileGuide(state, viewModel, padding, onPlay = play)
            tab == Tab.SEARCH -> SearchScreen(state, viewModel, padding, onPlay = play)
            tab == Tab.SETTINGS -> Box(Modifier.padding(padding)) {
                SetupScreen(onDone = { tab = Tab.HOME })
            }
        }
    }

    AnimatedVisibility(
        visible = playerOpen && state.currentChannel != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        PlayerPage(state, playback, viewModel, onCollapse = { playerOpen = false })
    }
}

/** A single category as a full list, reached from "See all" on the home screen. */
@Composable
private fun BrowsePage(
    group: String,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onPlay: (ChannelWithNow) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    val title = when (group) {
        FAVOURITES_GROUP -> "Favourites"
        "" -> "All channels"
        else -> group
    }
    val rows = remember(state.allChannels, group) {
        when (group) {
            FAVOURITES_GROUP -> state.allChannels.filter { it.isFavorite }
            "" -> state.allChannels
            else -> state.allChannels.filter { (it.channel.groupName ?: "Other") == group }
        }
    }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (rows.isEmpty()) {
            EmptyState(title = "Nothing here", action = "Settings", onAction = onOpenSettings)
        } else {
            LazyColumn {
                items(rows, key = { it.channel.id }) { row ->
                    ChannelRow(
                        row = row,
                        playing = row.channel.id == state.currentChannel?.id,
                        showNumber = state.showChannelNumbers,
                        onClick = { onPlay(row) },
                        onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                    )
                }
            }
        }
    }
}

/** Collapsed player above the tab bar, the way music apps do it. */
@Composable
private fun MiniPlayer(
    state: PlayerUiState,
    playback: Playback,
    viewModel: PlayerViewModel,
    onOpen: () -> Unit,
) {
    val channel = state.currentChannel ?: return
    val color = rememberChannelColor(channel.logo, channel.name)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Scrim.panelDeep)
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) { LogoOrInitials(channel.logo, channel.name, Modifier.fillMaxSize(0.7f)) }
        HSpace(10)
        Column(Modifier.weight(1f)) {
            Text(
                channel.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.nowProgramme?.title ?: if (state.isBuffering) "Connecting…" else "Live",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { playback.togglePlayPause() }) {
            Icon(
                if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playback.isPlaying) "Pause" else "Play",
            )
        }
        IconButton(onClick = { viewModel.stop() }) {
            Icon(Icons.Filled.Close, "Stop")
        }
    }
}
