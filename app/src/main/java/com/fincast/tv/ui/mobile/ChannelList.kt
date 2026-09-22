package com.fincast.tv.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.ui.components.HSpace
import com.fincast.tv.ui.components.ProgressLine
import com.fincast.tv.ui.components.TwoLine
import com.fincast.tv.ui.components.VSpace
import com.fincast.tv.ui.components.progressOf
import com.fincast.tv.ui.player.PlayerUiState
import com.fincast.tv.ui.player.PlayerViewModel
import com.fincast.tv.ui.theme.Scrim

/**
 * A channel as a colour tile: the logo on its own brand colour, with the
 * current programme underneath. This is the unit the home screen rows and
 * the guide's channel column are built from.
 */
@Composable
fun ChannelCard(
    row: ChannelWithNow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    playing: Boolean = false,
) {
    val color = rememberChannelColor(row.channel.logo, row.channel.name)
    Column(
        modifier
            .width(width)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(listOf(color, color.copy(alpha = 0.72f)))
                ),
            contentAlignment = Alignment.Center,
        ) {
            LogoOrInitials(row.channel.logo, row.channel.name, Modifier.fillMaxSize(0.62f))
            if (playing) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE53935))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            if (row.isFavorite) {
                Icon(
                    Icons.Filled.Star, null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(16.dp),
                )
            }
            if (row.nowStartMs != null && row.nowStopMs != null) {
                ProgressLine(
                    progressOf(row.nowStartMs, row.nowStopMs),
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    height = 3.dp,
                )
            }
        }
        VSpace(6)
        Text(
            text = row.channel.name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = row.nowTitle ?: "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp).height(16.dp),
        )
    }
}

@Composable
fun LogoOrInitials(logo: String?, name: String, modifier: Modifier = Modifier) {
    if (logo.isNullOrBlank()) {
        Text(
            text = name.split(' ').filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercase() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    } else {
        AsyncImage(model = logo, contentDescription = null, modifier = modifier)
    }
}

/** A horizontally scrolling row of cards with a section title. */
@Composable
fun CardRow(
    title: String,
    items: List<ChannelWithNow>,
    playingId: Long?,
    onClick: (ChannelWithNow) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onSeeAll != null) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.channel.id }) { row ->
            ChannelCard(row, onClick = { onClick(row) }, playing = row.channel.id == playingId)
        }
    }
}

/** Category chips plus the scrolling channel list; used by the full-list views. */
@Composable
internal fun ChannelBrowser(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onOpenSettings: () -> Unit,
    onPicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        GroupChips(state, viewModel)

        val nothingLoaded = state.playlists.isEmpty() ||
            (state.channels.isEmpty() && state.selectedGroup == null &&
                !state.favoritesOnly && state.query.isBlank())

        when {
            nothingLoaded -> EmptyState(
                title = "No channels loaded yet",
                detail = "Add your Xtream login or an M3U playlist to get started.",
                action = "Add a playlist",
                onAction = onOpenSettings,
            )

            state.channels.isEmpty() && state.favoritesOnly -> EmptyState(
                title = "No favourites yet",
                detail = "Tap the star next to a channel to keep it here.",
            )

            state.channels.isEmpty() -> EmptyState(title = "No matching channels")

            else -> {
                val listState = rememberLazyListState()
                // Open on the playing channel rather than the top of a long list.
                LaunchedEffect(Unit) {
                    val index = state.currentIndex
                    if (index > 2) listState.scrollToItem(index - 2)
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.channels, key = { it.channel.id }) { row ->
                        ChannelRow(
                            row = row,
                            playing = row.channel.id == state.currentChannel?.id,
                            showNumber = state.showChannelNumbers,
                            onClick = {
                                viewModel.tune(row.channel)
                                onPicked()
                            },
                            onFavorite = { viewModel.toggleFavorite(row.channel.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupChips(state: PlayerUiState, viewModel: PlayerViewModel) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.selectedGroup == null && !state.favoritesOnly,
                onClick = {
                    viewModel.setFavoritesOnly(false)
                    viewModel.setGroup(null)
                },
                label = { Text("All") },
                colors = colors,
            )
        }
        item {
            FilterChip(
                selected = state.favoritesOnly,
                onClick = {
                    viewModel.setGroup(null)
                    viewModel.setFavoritesOnly(true)
                },
                label = { Text("Favourites") },
                leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) },
                colors = colors,
            )
        }
        items(state.groups) { group ->
            FilterChip(
                selected = state.selectedGroup == group && !state.favoritesOnly,
                onClick = {
                    viewModel.setFavoritesOnly(false)
                    viewModel.setGroup(group)
                },
                label = { Text(group, maxLines = 1) },
                colors = colors,
            )
        }
    }
}

@Composable
internal fun ChannelRow(
    row: ChannelWithNow,
    playing: Boolean,
    showNumber: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val color = rememberChannelColor(row.channel.logo, row.channel.name)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (playing) Scrim.rowSelected.copy(alpha = 0.55f) else Color.Transparent)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNumber) {
            Text(
                text = row.channel.number.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
        }
        Box(
            Modifier
                .size(width = 64.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            LogoOrInitials(row.channel.logo, row.channel.name, Modifier.fillMaxSize(0.7f))
        }
        HSpace(12)
        Column(Modifier.weight(1f)) {
            TwoLine(primary = row.channel.name, secondary = row.nowTitle ?: "No programme info")
            if (row.nowStartMs != null && row.nowStopMs != null) {
                VSpace(4)
                ProgressLine(progressOf(row.nowStartMs, row.nowStopMs))
            }
        }
        FavoriteButton(row.isFavorite, onFavorite)
    }
}

@Composable
internal fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
            tint = if (favorite) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    detail: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        detail?.let {
            VSpace(6)
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            VSpace(16)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
