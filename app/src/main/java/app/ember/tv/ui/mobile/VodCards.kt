package app.ember.tv.ui.mobile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import app.ember.tv.data.db.ProgressEntity
import app.ember.tv.ui.components.ProgressLine
import app.ember.tv.ui.components.VSpace

/**
 * Artwork with a designed fallback. IPTV posters are often missing or dead
 * links, and a grid of grey boxes looks broken, so a missing poster becomes a
 * colour card with the title set on it.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val fallback = @Composable {
        val color = ChannelColors.fallback(title)
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
    Box(modifier) {
        if (url.isNullOrBlank()) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                error = { fallback() },
                loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
            )
        }
    }
}

/** A 2:3 poster with its title underneath, for film and series grids. */
@Composable
fun PosterCard(
    title: String,
    poster: String?,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            Artwork(poster, title, Modifier.fillMaxSize())
            if (progress != null && progress > 0f) {
                ProgressLine(
                    progress,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(6.dp),
                )
            }
        }
        VSpace(5)
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = caption.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
        )
    }
}

/** A titled horizontal row of posters. */
@Composable
fun <T> PosterRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    titleOf: (T) -> String,
    posterOf: (T) -> String?,
    captionOf: (T) -> String?,
    onClick: (T) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    posterWidth: Dp = 112.dp,
) {
    SectionHeader(title, onSeeAll)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = key) { item ->
            PosterCard(
                title = titleOf(item),
                poster = posterOf(item),
                caption = captionOf(item),
                onClick = { onClick(item) },
                modifier = Modifier.width(posterWidth),
            )
        }
    }
}

/** Wide cards with a progress bar: films and shows started but not finished. */
@Composable
fun ContinueWatchingRow(items: List<ProgressEntity>, onClick: (ProgressEntity) -> Unit) {
    SectionHeader("Continue watching", null)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.key }) { p ->
            Column(
                Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onClick(p) }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Artwork(p.poster, p.title, Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.5f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.7f),
                                )
                            )
                    )
                    if (p.durationMs > 0) {
                        ProgressLine(
                            (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f),
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                }
                VSpace(5)
                Text(
                    p.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                Text(
                    text = p.subtitle ?: remainingText(p),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

private fun remainingText(p: ProgressEntity): String {
    if (p.durationMs <= 0) return "Resume"
    val minutes = ((p.durationMs - p.positionMs) / 60_000).coerceAtLeast(1)
    return "$minutes min left"
}

@Composable
fun SectionHeader(title: String, onSeeAll: (() -> Unit)?) {
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
}

fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}
