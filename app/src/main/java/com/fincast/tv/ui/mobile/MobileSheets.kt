package com.fincast.tv.ui.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fincast.tv.Graph
import com.fincast.tv.data.db.ChannelEntity
import com.fincast.tv.data.db.ProgrammeEntity
import com.fincast.tv.data.model.CatchupType
import com.fincast.tv.ui.components.ChannelLogo
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.components.Pill
import com.fincast.tv.ui.components.ProgressLine
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.components.formatClock
import com.fincast.tv.ui.components.formatDay
import com.fincast.tv.ui.components.progressOf
import com.fincast.tv.ui.player.PlayerEngine
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.player.TrackOption

private const val HOUR_MS = 60 * 60 * 1000L
private const val DAY_MS = 24 * HOUR_MS

/** Channel list reachable from fullscreen, where the inline list is hidden. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelSheet(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ChannelBrowser(
            state = state,
            viewModel = viewModel,
            onOpenSettings = onOpenSettings,
            onPicked = onDismiss,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        )
    }
}

/**
 * One channel's schedule: what's on now and later, plus earlier shows that
 * can be replayed when the provider offers catch-up. This is the phone's
 * stand-in for the TV guide grid, which needs a remote to be usable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSheet(
    channel: ChannelEntity,
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val hasCatchup = channel.catchupType != CatchupType.NONE
    // Show the archive window when there is one, else a few hours of context.
    val from = now - if (hasCatchup) channel.catchupDays.coerceAtLeast(1) * DAY_MS else 3 * HOUR_MS
    var programmes by remember(channel.id) { mutableStateOf<List<ProgrammeEntity>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(channel.id) {
        programmes = Graph.epg.schedule(channel.tvgId, from, now + DAY_MS)
    }
    LaunchedEffect(programmes) {
        val index = programmes?.indexOfFirst { it.stopMs > now } ?: -1
        if (index > 0) listState.scrollToItem(index)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelLogo(channel.logo, channel.name, size = 40.dp)
                HSpace(12)
                Column {
                    Text(channel.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (hasCatchup) {
                            "Catch-up: ${channel.catchupDays} days · tap an earlier show to replay"
                        } else {
                            "No catch-up on this channel"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val list = programmes
            when {
                list == null -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                list.isEmpty() -> EmptyState(
                    title = "No guide data for this channel",
                    detail = "The provider's EPG has no listings matching this channel.",
                )

                else -> LazyColumn(state = listState) {
                    itemsIndexed(list, key = { _, it -> it.id }) { index, programme ->
                        if (index == 0 || formatDay(list[index - 1].startMs) != formatDay(programme.startMs)) {
                            Text(
                                text = formatDay(programme.startMs),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        ScheduleRow(
                            programme = programme,
                            now = now,
                            canReplay = hasCatchup,
                            onClick = {
                                val live = programme.startMs <= now && programme.stopMs > now
                                when {
                                    // Tuning the channel that is already current would not
                                    // change its id, so leave catch-up explicitly.
                                    live && state.currentChannel?.id == channel.id -> viewModel.returnToLive()
                                    live -> viewModel.tune(channel)
                                    else -> viewModel.startCatchup(channel, programme)
                                }
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    programme: ProgrammeEntity,
    now: Long,
    canReplay: Boolean,
    onClick: () -> Unit,
) {
    val live = programme.startMs <= now && programme.stopMs > now
    val past = programme.stopMs <= now
    val tappable = live || (past && canReplay)
    val dim = past && !canReplay
    val textColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = tappable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClock(programme.startMs),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = programme.title,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            programme.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (live) {
                VSpace(4)
                ProgressLine(progressOf(programme.startMs, programme.stopMs, now), Modifier.fillMaxWidth())
            }
        }
        when {
            live -> {
                HSpace(8)
                Pill("LIVE")
            }
            past && canReplay -> {
                HSpace(8)
                Icon(Icons.Filled.Replay, "Replay", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun TracksDialog(engine: PlayerEngine, onDismiss: () -> Unit) {
    // Selection is updated optimistically: the player reports the new track
    // asynchronously, and the dialog should not flicker back while it does.
    var audio by remember { mutableStateOf(engine.audioTracks()) }
    var subtitles by remember { mutableStateOf(engine.subtitleTracks()) }

    fun pick(list: List<TrackOption>, chosen: TrackOption?) =
        list.map { it.copy(isSelected = it == chosen) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Audio & subtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("Audio")
                if (audio.isEmpty()) {
                    Hint("This stream has a single audio track")
                } else {
                    audio.forEach { option ->
                        TrackRow(option.label, option.isSelected) {
                            engine.selectTrack(option)
                            audio = pick(audio, option)
                        }
                    }
                }
                VSpace(12)
                SectionLabel("Subtitles")
                if (subtitles.isEmpty()) {
                    Hint("This stream has no subtitles")
                } else {
                    TrackRow("Off", subtitles.none { it.isSelected }) {
                        engine.disableSubtitles()
                        subtitles = pick(subtitles, null)
                    }
                    subtitles.forEach { option ->
                        TrackRow(option.label, option.isSelected) {
                            engine.selectTrack(option)
                            subtitles = pick(subtitles, option)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        HSpace(8)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
