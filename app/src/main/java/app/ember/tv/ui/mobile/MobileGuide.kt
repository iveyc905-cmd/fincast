package app.ember.tv.ui.mobile

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ember.tv.Graph
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.data.db.ProgrammeEntity
import app.ember.tv.data.model.CatchupType
import app.ember.tv.ui.components.VSpace
import app.ember.tv.ui.components.formatClock
import app.ember.tv.ui.components.formatDay
import app.ember.tv.ui.player.PlayerUiState
import app.ember.tv.ui.player.PlayerViewModel
import java.util.concurrent.TimeUnit

private val DP_PER_MINUTE = 4.dp
private val TILE_WIDTH = 76.dp
private val ROW_HEIGHT = 58.dp
private const val SLOT_MIN = 30L
private const val WINDOW_HOURS = 14L

/**
 * A touch guide: drag sideways through time, scroll down through channels.
 *
 * One [ScrollState] is shared by the time ruler and every row, so they move
 * together. Rows fetch their own listings when they scroll into view, so a
 * 20,000-channel playlist costs no more to open than a 20-channel one.
 */
@Composable
fun MobileGuide(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    contentPadding: PaddingValues,
    onPlay: (ChannelWithNow) -> Unit,
    /** Opens the player without tuning, for catch-up the view model has already started. */
    onOpenPlayer: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val slotMs = TimeUnit.MINUTES.toMillis(SLOT_MIN)
    // Start one slot before the current half-hour so "now" is never at the edge.
    val windowStart = remember(now) { (now / slotMs) * slotMs - slotMs }
    val windowEnd = windowStart + TimeUnit.HOURS.toMillis(WINDOW_HOURS)
    val timeline = rememberScrollState()
    val density = LocalDensity.current

    // Open with the current time a little in from the left edge.
    LaunchedEffect(Unit) {
        val nowDp = minutesToDp(now - windowStart) - 36.dp
        timeline.scrollTo(with(density) { nowDp.roundToPx() }.coerceAtLeast(0))
    }

    val listings = remember(windowStart) { mutableStateMapOf<String, List<ProgrammeEntity>>() }
    var selected by remember { mutableStateOf<Pair<ChannelWithNow, ProgrammeEntity>?>(null) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Guide",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )
        GroupChips(state, viewModel)
        TimeRuler(windowStart, windowEnd, now, timeline)

        if (state.channels.isEmpty()) {
            EmptyState(title = "No channels in this category")
            return@Column
        }

        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            items(state.channels, key = { it.channel.id }) { row ->
                val tvgId = row.channel.tvgId
                if (tvgId != null && tvgId !in listings) {
                    LaunchedEffect(tvgId, windowStart) {
                        listings[tvgId] = Graph.epg.schedule(tvgId, windowStart, windowEnd)
                    }
                }
                GuideRow(
                    row = row,
                    programmes = tvgId?.let { listings[it] },
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = now,
                    timeline = timeline,
                    playing = row.channel.id == state.currentChannel?.id,
                    onTile = { onPlay(row) },
                    onProgramme = { selected = row to it },
                )
            }
        }
    }

    selected?.let { (row, programme) ->
        ProgrammeDialog(
            row = row,
            programme = programme,
            now = now,
            onDismiss = { selected = null },
            onWatch = {
                selected = null
                onPlay(row)
            },
            onReplay = {
                selected = null
                viewModel.startCatchup(row.channel, programme)
                onOpenPlayer()
            },
        )
    }
}

private fun minutesToDp(ms: Long): Dp = DP_PER_MINUTE * (ms / 60_000f)

@Composable
private fun TimeRuler(windowStart: Long, windowEnd: Long, now: Long, timeline: ScrollState) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDay(now),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.width(TILE_WIDTH).padding(start = 12.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(timeline)
        ) {
            Box(Modifier.width(minutesToDp(windowEnd - windowStart)).fillMaxHeight()) {
                var slot = windowStart
                val slotMs = TimeUnit.MINUTES.toMillis(SLOT_MIN)
                while (slot < windowEnd) {
                    Text(
                        text = formatClock(slot),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .offset(x = minutesToDp(slot - windowStart) + 4.dp)
                            .align(Alignment.CenterStart),
                    )
                    slot += slotMs
                }
                NowMarker(windowStart, now)
            }
        }
    }
}

@Composable
private fun NowMarker(windowStart: Long, now: Long) {
    Box(
        Modifier
            .offset(x = minutesToDp(now - windowStart))
            .width(2.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun GuideRow(
    row: ChannelWithNow,
    programmes: List<ProgrammeEntity>?,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    timeline: ScrollState,
    playing: Boolean,
    onTile: () -> Unit,
    onProgramme: (ProgrammeEntity) -> Unit,
) {
    val color = rememberChannelColor(row.channel.logo, row.channel.name)
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        // Channel tile: fixed on the left, tap to watch.
        Box(
            Modifier
                .width(TILE_WIDTH)
                .fillMaxHeight()
                .padding(start = 8.dp, end = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .clickable(onClick = onTile),
            contentAlignment = Alignment.Center,
        ) {
            LogoOrInitials(row.channel.logo, row.channel.name, Modifier.fillMaxSize(0.72f))
            if (playing) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0xFFE53935))
                )
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(timeline)
        ) {
            Box(Modifier.width(minutesToDp(windowEnd - windowStart)).fillMaxHeight()) {
                when {
                    programmes == null -> Unit // still loading
                    programmes.isEmpty() -> GuideBlock(
                        title = "No guide data",
                        color = Color.White.copy(alpha = 0.06f),
                        modifier = Modifier
                            .offset(x = minutesToDp(now - windowStart) - 36.dp)
                            .width(220.dp),
                        dim = true,
                    )
                    else -> programmes.forEach { p ->
                        val start = p.startMs.coerceAtLeast(windowStart)
                        val stop = p.stopMs.coerceAtMost(windowEnd)
                        if (stop <= start) return@forEach
                        val live = p.startMs <= now && p.stopMs > now
                        val past = p.stopMs <= now
                        GuideBlock(
                            title = p.title,
                            subtitle = formatClock(p.startMs),
                            color = color.copy(alpha = if (live) 0.95f else if (past) 0.28f else 0.5f),
                            bold = live,
                            modifier = Modifier
                                .offset(x = minutesToDp(start - windowStart))
                                .width(minutesToDp(stop - start)),
                            onClick = { onProgramme(p) },
                        )
                    }
                }
                NowMarker(windowStart, now)
            }
        }
    }
}

@Composable
private fun GuideBlock(
    title: String,
    color: Color,
    modifier: Modifier,
    subtitle: String? = null,
    bold: Boolean = false,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxHeight()
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProgrammeDialog(
    row: ChannelWithNow,
    programme: ProgrammeEntity,
    now: Long,
    onDismiss: () -> Unit,
    onWatch: () -> Unit,
    onReplay: () -> Unit,
) {
    val live = programme.startMs <= now && programme.stopMs > now
    val past = programme.stopMs <= now
    val canReplay = past && row.channel.catchupType != CatchupType.NONE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(programme.title) },
        text = {
            Column {
                Text(
                    text = "${row.channel.name}  ·  ${formatClock(programme.startMs)}–${formatClock(programme.stopMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                programme.description?.takeIf { it.isNotBlank() }?.let {
                    VSpace(8)
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 8,
                        overflow = TextOverflow.Ellipsis)
                }
                if (past && !canReplay) {
                    VSpace(8)
                    Text(
                        "Already aired; this channel has no catch-up.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when {
                live -> TextButton(onClick = onWatch) { Text("Watch now") }
                canReplay -> TextButton(onClick = onReplay) { Text("Replay") }
                else -> TextButton(onClick = onWatch) { Text("Watch channel") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
