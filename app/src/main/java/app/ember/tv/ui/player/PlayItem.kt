package app.ember.tv.ui.player

/**
 * One thing to play — a live channel, a catch-up programme, a film or an
 * episode — in the shape both the local player and a Cast device need.
 */
data class PlayItem(
    /** Identity for de-duplicating loads; differs for live vs catch-up of the same channel. */
    val key: String,
    val url: String,
    val referrer: String?,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
    val isLive: Boolean,
    val startPositionMs: Long = 0,
    /** Set for films and episodes, whose position is saved for "continue watching". */
    val progress: ProgressRef? = null,
)

data class ProgressRef(
    val key: String,
    val url: String,
    val playlistId: Long,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val seriesId: Long?,
)

/**
 * A request from the view model for the player host to act on. The token
 * makes "play the same thing again" (a retry, a restart) observable as a change.
 */
data class PlayRequest(val token: Long, val item: PlayItem?)
