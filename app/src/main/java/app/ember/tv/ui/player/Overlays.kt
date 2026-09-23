package app.ember.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.ui.components.ChannelLogo
import app.ember.tv.ui.components.HSpace
import app.ember.tv.ui.components.Pill
import app.ember.tv.ui.components.ProgressLine
import app.ember.tv.ui.components.SelectableRow
import app.ember.tv.ui.components.TwoLine
import app.ember.tv.ui.components.VSpace
import app.ember.tv.ui.components.formatClock
import app.ember.tv.ui.components.progressOf
import app.ember.tv.ui.theme.Scrim

private const val ALL_GROUPS = "All channels"
private const val FAVORITES = "Favourites"

/**
 * The channel browser: categories on the left, channels on the right, video
 * still playing behind both.
 *
 * Selection is tracked as an index rather than delegated to Compose focus
 * traversal. With lists this long, index-driven selection keeps the scroll
 * position, the highlight, and the "what would Enter do" answer in one place.
 */
@Composable
fun ChannelPanel(state: PlayerUiState, viewModel: PlayerViewModel) {
    val groups = remember(state.groups) { listOf(ALL_GROUPS, FAVORITES) + state.groups }
    var inGroups by remember { mutableStateOf(false) }
    var groupIndex by remember { mutableIntStateOf(0) }
    var channelIndex by remember { mutableIntStateOf(0) }

    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Open on the channel that is playing, so Enter re-selects it and up/down
    // move relative to it.
    LaunchedEffect(state.currentChannel?.id, state.channels.size) {
        val index = state.currentIndex
        if (index >= 0) {
            channelIndex = index
            channelListState.scrollToItem(index)
        }
    }
    LaunchedEffect(channelIndex) {
        if (state.channels.isNotEmpty()) {
            channelListState.animateScrollToItem(channelIndex.coerceIn(0, state.channels.lastIndex))
        }
    }
    LaunchedEffect(groupIndex) { groupListState.animateScrollToItem(groupIndex) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun applyGroup(index: Int) {
        when (groups.getOrNull(index)) {
            null -> Unit
            ALL_GROUPS -> {
                viewModel.setFavoritesOnly(false); viewModel.setGroup(null)
            }
            FAVORITES -> {
                viewModel.setFavoritesOnly(true); viewModel.setGroup(null)
            }
            else -> {
                viewModel.setFavoritesOnly(false); viewModel.setGroup(groups[index])
            }
        }
        channelIndex = 0
    }

    Row(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (inGroups) groupIndex = (groupIndex - 1 + groups.size) % groups.size
                        else if (state.channels.isNotEmpty()) {
                            channelIndex = (channelIndex - 1 + state.channels.size) % state.channels.size
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (inGroups) groupIndex = (groupIndex + 1) % groups.size
                        else if (state.channels.isNotEmpty()) {
                            channelIndex = (channelIndex + 1) % state.channels.size
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (inGroups) viewModel.closeOverlay() else inGroups = true
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (inGroups) {
                            applyGroup(groupIndex)
                            inGroups = false
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        if (inGroups) {
                            applyGroup(groupIndex)
                            inGroups = false
                        } else {
                            state.channels.getOrNull(channelIndex)?.let {
                                viewModel.tune(it.channel)
                                viewModel.closeOverlay()
                            }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_PROG_RED, AndroidKeyEvent.KEYCODE_BOOKMARK -> {
                        state.channels.getOrNull(channelIndex)
                            ?.let { viewModel.toggleFavorite(it.channel.id) }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_GUIDE -> {
                        viewModel.openOverlay(Overlay.GUIDE); true
                    }
                    else -> false
                }
            }
    ) {
        // Category column
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(Scrim.panelDeep)
                .padding(vertical = 20.dp, horizontal = 10.dp)
        ) {
            Text(
                "Categories",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            LazyColumn(state = groupListState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(groups) { index, group ->
                    SelectableRow(selected = inGroups && index == groupIndex) {
                        Text(
                            text = group,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActiveGroup(group, state)) FontWeight.Bold
                            else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        // Channel column
        Column(
            Modifier
                .width(520.dp)
                .fillMaxHeight()
                .background(Scrim.panel)
                .padding(vertical = 20.dp, horizontal = 10.dp)
        ) {
            Row(
                Modifier.padding(start = 12.dp, bottom = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentGroupLabel(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Pill("${state.channels.size}")
            }

            if (state.channels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No channels here",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = channelListState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(state.channels, key = { _, it -> it.channel.id }) { index, row ->
                        ChannelRow(
                            row = row,
                            selected = !inGroups && index == channelIndex,
                            playing = row.channel.id == state.currentChannel?.id,
                            showNumber = state.showChannelNumbers,
                        )
                    }
                }
            }
        }

        // The rest of the screen stays clear so the video remains watchable.
        Box(Modifier.weight(1f).fillMaxHeight())
    }
}

private fun isActiveGroup(group: String, state: PlayerUiState): Boolean = when (group) {
    ALL_GROUPS -> state.selectedGroup == null && !state.favoritesOnly
    FAVORITES -> state.favoritesOnly
    else -> state.selectedGroup == group
}

private fun currentGroupLabel(state: PlayerUiState): String = when {
    state.favoritesOnly -> FAVORITES
    state.selectedGroup != null -> state.selectedGroup
    else -> ALL_GROUPS
}

@Composable
private fun ChannelRow(
    row: ChannelWithNow,
    selected: Boolean,
    playing: Boolean,
    showNumber: Boolean,
) {
    SelectableRow(selected = selected) {
        if (showNumber) {
            Text(
                text = row.channel.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp),
            )
        }
        ChannelLogo(row.channel.logo, row.channel.name)
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TwoLine(
                primary = row.channel.name,
                secondary = row.nowTitle ?: "No programme data",
            )
            if (row.nowStartMs != null && row.nowStopMs != null) {
                VSpace(4)
                ProgressLine(progressOf(row.nowStartMs, row.nowStopMs))
            }
        }
        if (row.isFavorite) {
            HSpace(8)
            Text("★", color = MaterialTheme.colorScheme.secondary)
        }
        if (playing) {
            HSpace(8)
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** Bottom bar with the channel, what is on now, and what is next. */
@Composable
fun InfoBar(state: PlayerUiState, viewModel: PlayerViewModel) {
    val channel = state.currentChannel
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { viewModel.zap(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { viewModel.zap(1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER -> {
                        viewModel.openOverlay(Overlay.CHANNELS); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Scrim.bar)
                .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel?.logo, channel?.name.orEmpty(), size = 56.dp)
                HSpace(16)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            channel?.number?.let { append(it).append("  ") }
                            append(channel?.name ?: "No channel")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.nowProgramme?.let { now ->
                        VSpace(4)
                        Text(
                            text = "${formatClock(now.startMs)} – ${formatClock(now.stopMs)}   ${now.title}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        VSpace(6)
                        ProgressLine(progressOf(now.startMs, now.stopMs), Modifier.fillMaxWidth())
                    }
                    state.nextProgramme?.let { next ->
                        VSpace(6)
                        Text(
                            text = "Next  ${formatClock(next.startMs)}  ${next.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.catchup?.let {
                    HSpace(16)
                    Pill("CATCH-UP")
                }
            }

            state.nowProgramme?.description?.takeIf { it.isNotBlank() }?.let { description ->
                VSpace(10)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Audio and subtitle track picker. */
@Composable
fun TrackPanel(engine: PlayerEngine, viewModel: PlayerViewModel) {
    val audio = remember { engine.audioTracks() }
    val subtitles = remember { engine.subtitleTracks() }
    val options = remember(audio, subtitles) {
        buildList {
            addAll(audio.map { "Audio" to it })
            addAll(subtitles.map { "Subtitle" to it })
        }
    }
    var index by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (options.isNotEmpty()) index = (index - 1 + options.size) % options.size
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (options.isNotEmpty()) index = (index + 1) % options.size
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        options.getOrNull(index)?.let { (_, option) -> engine.selectTrack(option) }
                        viewModel.closeOverlay()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Scrim.panelDeep)
                .padding(24.dp)
        ) {
            Text("Tracks", style = MaterialTheme.typography.titleMedium)
            VSpace(12)
            if (options.isEmpty()) {
                Text(
                    "This stream exposes only one track",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                options.forEachIndexed { i, (kind, option) ->
                    SelectableRow(selected = i == index) {
                        Text(kind, modifier = Modifier.width(90.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.isSelected) {
                            HSpace(8)
                            Text("●", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/** Quick actions reachable from the remote's menu button. */
@Composable
fun MenuPanel(state: PlayerUiState, viewModel: PlayerViewModel, onOpenSettings: () -> Unit) {
    val actions = remember(state.catchup, state.playlists) {
        buildList<Pair<String, () -> Unit>> {
            add("Channel guide" to { viewModel.openOverlay(Overlay.GUIDE) })
            add("Audio & subtitles" to { viewModel.openOverlay(Overlay.TRACKS) })
            add("Aspect ratio" to { viewModel.cycleAspect() })
            add("Toggle favourite" to { viewModel.toggleFavorite() })
            if (state.catchup != null) add("Back to live" to { viewModel.returnToLive() })
            add("Settings & playlists" to { onOpenSettings() })
        }
    }
    var index by remember { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        index = (index - 1 + actions.size) % actions.size; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        index = (index + 1) % actions.size; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        viewModel.closeOverlay()
                        actions[index].second()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Scrim.panelDeep)
                .padding(24.dp)
        ) {
            actions.forEachIndexed { i, (label, _) ->
                SelectableRow(selected = i == index) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
