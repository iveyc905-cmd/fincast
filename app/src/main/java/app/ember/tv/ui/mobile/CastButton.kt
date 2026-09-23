package app.ember.tv.ui.mobile

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

/**
 * The standard Cast icon. It hides itself when no Cast device is on the
 * network and animates while connecting; tapping it opens the system device
 * picker. Only shown by callers that have a working Cast context.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).also { button ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, button) }
            }
        },
        modifier = modifier.size(48.dp),
    )
}
