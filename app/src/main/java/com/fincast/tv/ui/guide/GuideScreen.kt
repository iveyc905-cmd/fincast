package com.fincast.tv.ui.guide

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fincast.tv.Graph
import com.fincast.tv.data.db.ProgrammeEntity
import com.fincast.tv.ui.components.ChannelLogo
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.components.formatClock
import com.fincast.tv.ui.components.formatDay
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.theme.Scrim
import java.util.concurrent.TimeUnit

private val ROW_HEIGHT = 64.dp
private val CHANNEL_COLUMN = 240.dp
private const val WINDOW_HOURS = 6L
private const val SLOT_MINUTES = 30L

/**
 * A time-grid EPG.
 *
 * Navigation is anchored to a cursor time rather than to programme indices:
 * moving up or down keeps the same moment and lands on whatever airs then,
 * which is what makes a grid guide feel like a grid rather than a set of
 * unrelated lists.
 */
@Composable
fun GuideOverlay(state: PlayerUiState, viewModel: PlayerViewModel) {
    val channels = state.channels
    var rowIndex by remember { mutableIntStateOf(state.currentIndex.coerceAtLeast(0)) }
    var cursorMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var schedule by remember { mutableStateOf<Map<String, List<ProgrammeEntity>>>(emptyMap()) }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Window start snaps to the half hour so the header labels stay tidy.
    val windowStart = remember(cursorMs) {
        val slot = TimeUnit.MINUTES.toMillis(SLOT_MINUTES)
        (cursorMs / slot) * slot - slot
    }
    val windowEnd = windowStart + TimeUnit.HOURS.toMillis(WINDOW_HOURS)

    LaunchedEffect(channels, windowStart) {
        val ids = channels.mapNotNull { it.channel.tvgId }.distinct()
        schedule = Graph.epg.grid(ids, windowStart, windowEnd)
    }
    LaunchedEffect(rowIndex) {
        if (channels.isNotEmpty()) listState.animateScrollToItem(rowIndex.coerceIn(0, channels.lastIndex))
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        listState.scrollToItem(rowIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
    }

    fun programmesFor(index: Int): List<ProgrammeEntity> =
        channels.getOrNull(index)?.channel?.tvgId?.let { schedule[it] }.orEmpty()

    fun selectedProgramme(): ProgrammeEntity? =
        programmesFor(rowIndex).firstOrNull { cursorMs >= it.startMs && cursorMs < it.stopMs }

    fun step(direction: Int) {
        val programmes = programmesFor(rowIndex)
        val current = selectedProgramme()
        val next = when {
            current == null -> programmes.firstOrNull()
            direction > 0 -> programmes.firstOrNull { it.startMs >= current.stopMs }
            else -> programmes.lastOrNull { it.stopMs <= current.startMs }
        }
        cursorMs = when {
            next != null -> next.startMs
            // Past the edge of loaded data: shift the window by a slot so the
            // next load fills it in.
            else -> cursorMs + direction * TimeUnit.MINUTES.toMillis(SLOT_MINUTES)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim.panelDeep)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (channels.isNotEmpty()) rowIndex = (rowIndex - 1 + channels.size) % channels.size
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (channels.isNotEmpty()) rowIndex = (rowIndex + 1) % channels.size
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        val channel = channels.getOrNull(rowIndex)?.channel
                        val programme = selectedProgramme()
                        when {
                            channel == null -> Unit
                            programme != null && programme.stopMs < System.currentTimeMillis() ->
                                viewModel.startCatchup(channel, programme)
                            else -> {
                                viewModel.tune(channel)
                                viewModel.closeOverlay()
                            }
                        }
                        true
                    }
                    AndroidKeyEvent.KEYCODE_PROG_RED, AndroidKeyEvent.KEYCODE_BOOKMARK -> {
                        channels.getOrNull(rowIndex)?.let { viewModel.toggleFavorite(it.channel.id) }
                        true
                    }
                    else -> false
                }
            }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val gridWidth = maxWidth - CHANNEL_COLUMN
            val pxPerMinute: Dp = gridWidth / (WINDOW_HOURS * 60f)

            fun widthFor(startMs: Long, stopMs: Long): Dp {
                val clampedStart = startMs.coerceAtLeast(windowStart)
                val clampedStop = stopMs.coerceAtMost(windowEnd)
                val minutes = ((clampedStop - clampedStart) / 60_000f).coerceAtLeast(0f)
                return pxPerMinute * minutes
            }

            fun offsetFor(startMs: Long): Dp =
                pxPerMinute * ((startMs.coerceAtLeast(windowStart) - windowStart) / 60_000f)

            Column(Modifier.fillMaxSize().padding(20.dp)) {
                // Header: day plus the half-hour ruler
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatDay(cursorMs),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.width(CHANNEL_COLUMN),
                    )
                    Box(Modifier.fillMaxWidth().height(28.dp)) {
                        var slot = windowStart
                        while (slot < windowEnd) {
                            Text(
                                text = formatClock(slot),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(x = offsetFor(slot)),
                            )
                            slot += TimeUnit.MINUTES.toMillis(SLOT_MINUTES)
                        }
                    }
                }

                VSpace(8)

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(channels, key = { _, it -> it.channel.id }) { index, row ->
                        Row(Modifier.height(ROW_HEIGHT)) {
                            Row(
                                Modifier
                                    .width(CHANNEL_COLUMN)
                                    .fillMaxHeight()
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = row.channel.number.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(40.dp),
                                )
                                ChannelLogo(row.channel.logo, row.channel.name, size = 36.dp)
                                HSpace(8)
                                Text(
                                    text = row.channel.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (index == rowIndex) FontWeight.Bold
                                    else FontWeight.Normal,
                                )
                            }

                            Box(Modifier.fillMaxWidth().fillMaxHeight()) {
                                val programmes = row.channel.tvgId
                                    ?.let { schedule[it] }.orEmpty()
                                    .filter { it.stopMs > windowStart && it.startMs < windowEnd }

                                if (programmes.isEmpty()) {
                                    GuideBlock(
                                        title = "No guide data",
                                        modifier = Modifier.fillMaxWidth(),
                                        selected = false,
                                        dim = true,
                                    )
                                } else {
                                    programmes.forEach { programme ->
                                        val isSelected = index == rowIndex &&
                                            cursorMs >= programme.startMs && cursorMs < programme.stopMs
                                        GuideBlock(
                                            title = programme.title,
                                            subtitle = formatClock(programme.startMs),
                                            selected = isSelected,
                                            past = programme.stopMs < System.currentTimeMillis(),
                                            modifier = Modifier
                                                .offset(x = offsetFor(programme.startMs))
                                                .width(widthFor(programme.startMs, programme.stopMs)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Detail strip for whatever the cursor is on
                selectedProgramme()?.let { programme ->
                    VSpace(10)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Scrim.row)
                            .padding(16.dp)
                    ) {
                        Text(programme.title, style = MaterialTheme.typography.titleMedium)
                        VSpace(4)
                        Text(
                            text = "${formatClock(programme.startMs)} – ${formatClock(programme.stopMs)}" +
                                (programme.category?.let { "   ·   $it" } ?: "") +
                                if (programme.stopMs < System.currentTimeMillis())
                                    "   ·   press OK to replay" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        programme.description?.takeIf { it.isNotBlank() }?.let {
                            VSpace(6)
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBlock(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    past: Boolean = false,
    dim: Boolean = false,
) {
    Box(
        modifier
            .fillMaxHeight()
            .padding(end = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> Scrim.rowSelected
                    past -> Color.White.copy(alpha = 0.04f)
                    else -> Scrim.row
                }
            )
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(6.dp),
                ) else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
