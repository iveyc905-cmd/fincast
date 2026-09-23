package app.ember.tv.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ember.tv.Graph
import app.ember.tv.data.db.ChannelEntity
import app.ember.tv.data.db.ChannelWithNow
import app.ember.tv.data.db.EpisodeEntity
import app.ember.tv.data.db.MovieEntity
import app.ember.tv.data.db.ProgressEntity
import app.ember.tv.data.db.SeriesEntity
import app.ember.tv.data.db.PlaylistEntity
import app.ember.tv.data.db.ProgrammeEntity
import app.ember.tv.data.model.CatchupType
import app.ember.tv.data.model.SourceKind
import app.ember.tv.data.net.XtreamClient
import app.ember.tv.data.repo.AspectMode
import app.ember.tv.data.repo.VodRepository
import app.ember.tv.sync.SyncScheduler
import app.ember.tv.util.CatchupUrls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Overlay { NONE, CHANNELS, GUIDE, INFO, TRACKS, MENU }

/** A programme being replayed from the provider's archive rather than live. */
data class CatchupSession(val programme: ProgrammeEntity, val channelId: Long)

/** A film or episode being played, as the player page describes it. */
data class VodSession(
    /** "movie" or "episode". */
    val kind: String,
    val title: String,
    val subtitle: String?,
    val poster: String?,
    val plot: String?,
    val movieId: Long? = null,
    val seriesId: Long? = null,
    val episodeId: Long? = null,
    /** The following episode, for autoplay and the "Next episode" button. */
    val next: EpisodeEntity? = null,
)

data class PlayerUiState(
    val playlists: List<PlaylistEntity> = emptyList(),
    val activePlaylistId: Long = 0,
    val groups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    val channels: List<ChannelWithNow> = emptyList(),
    val currentChannel: ChannelEntity? = null,
    val nowProgramme: ProgrammeEntity? = null,
    val nextProgramme: ProgrammeEntity? = null,
    val overlay: Overlay = Overlay.NONE,
    val numberEntry: String = "",
    val statusMessage: String? = null,
    val isBuffering: Boolean = false,
    val aspectMode: AspectMode = AspectMode.FIT,
    val catchup: CatchupSession? = null,
    val showChannelNumbers: Boolean = true,
    /** Every channel in the playlist, unfiltered: what the home screen sections are built from. */
    val allChannels: List<ChannelWithNow> = emptyList(),
    val recent: List<ChannelWithNow> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ChannelWithNow> = emptyList(),
    val vod: VodSession? = null,
    /** A pull-to-refresh is reloading the active playlist. */
    val refreshing: Boolean = false,
) {
    val currentIndex: Int
        get() = channels.indexOfFirst { it.channel.id == currentChannel?.id }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val playlists = Graph.playlists
    private val epg = Graph.epg
    private val settings = Graph.settings
    private val vodRepo = Graph.vod

    /** What the player host should be playing; see [PlayRequest]. */
    val playRequest = MutableStateFlow<PlayRequest?>(null)
    private var playToken = 0L

    private fun request(item: PlayItem?) {
        playRequest.value = PlayRequest(++playToken, item)
    }

    private fun liveItem(channel: ChannelEntity) = PlayItem(
        key = "live:${channel.id}",
        url = channel.url,
        referrer = channel.referrer,
        title = channel.name,
        subtitle = null,
        artwork = channel.logo,
        isLive = true,
    )

    private val filter = MutableStateFlow(ChannelFilter())
    private val local = MutableStateFlow(PlayerUiState())

    private data class ChannelFilter(
        val playlistId: Long = 0,
        val group: String? = null,
        val favoritesOnly: Boolean = false,
        val query: String = "",
    )

    /** Re-emitted every minute so now/next progress bars advance on their own. */
    private val clock = MutableStateFlow(System.currentTimeMillis())

    private val channelStream: StateFlow<List<ChannelWithNow>> =
        combine(filter, clock) { f, now -> f to now }
            .flatMapLatest { (f, now) ->
                playlists.observeChannels(f.playlistId, f.group, f.favoritesOnly, f.query, now)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val playlistId = MutableStateFlow(0L)
    private val searchQuery = MutableStateFlow("")

    private val allChannelStream: StateFlow<List<ChannelWithNow>> =
        combine(playlistId, clock) { id, now -> id to now }
            .flatMapLatest { (id, now) -> playlists.observeChannels(id, null, false, "", now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val recentStream: StateFlow<List<ChannelWithNow>> =
        combine(playlistId, clock) { id, now -> id to now }
            .flatMapLatest { (id, now) -> playlists.observeRecent(id, now = now) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val searchStream: StateFlow<List<ChannelWithNow>> =
        combine(playlistId, searchQuery.debounce(150)) { id, q -> id to q }
            .flatMapLatest { (id, q) ->
                if (q.isBlank()) flowOf(emptyList())
                else playlists.observeChannels(id, null, false, q, System.currentTimeMillis())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<PlayerUiState> = local.asStateFlow()

    private var numberEntryJob: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                clock.value = System.currentTimeMillis()
                refreshNowNext()
            }
        }
        viewModelScope.launch {
            playlists.observePlaylists().collect { list ->
                local.value = local.value.copy(playlists = list)
                val active = local.value.activePlaylistId
                if (list.none { it.id == active }) {
                    list.firstOrNull()?.let { selectPlaylist(it.id) }
                }
            }
        }
        viewModelScope.launch {
            channelStream.collect { channels ->
                local.value = local.value.copy(channels = channels)
            }
        }
        viewModelScope.launch {
            allChannelStream.collect { local.value = local.value.copy(allChannels = it) }
        }
        viewModelScope.launch {
            recentStream.collect { local.value = local.value.copy(recent = it) }
        }
        viewModelScope.launch {
            searchStream.collect { local.value = local.value.copy(searchResults = it) }
        }
        viewModelScope.launch {
            filter
                .flatMapLatest { playlists.observeGroups(it.playlistId) }
                .collect { groups -> local.value = local.value.copy(groups = groups) }
        }
        viewModelScope.launch {
            settings.settings.collect { s ->
                local.value = local.value.copy(
                    aspectMode = s.aspectMode,
                    showChannelNumbers = s.showChannelNumbers,
                )
                if (local.value.activePlaylistId == 0L && s.activePlaylistId != 0L) {
                    selectPlaylist(s.activePlaylistId)
                }
            }
        }
    }

    fun selectPlaylist(id: Long) {
        local.value = local.value.copy(activePlaylistId = id, selectedGroup = null)
        filter.value = filter.value.copy(playlistId = id, group = null)
        playlistId.value = id
        viewModelScope.launch { settings.setActivePlaylist(id) }
    }

    fun setGroup(group: String?) {
        local.value = local.value.copy(selectedGroup = group)
        filter.value = filter.value.copy(group = group)
    }

    fun setFavoritesOnly(enabled: Boolean) {
        local.value = local.value.copy(favoritesOnly = enabled)
        filter.value = filter.value.copy(favoritesOnly = enabled)
    }

    fun setQuery(query: String) {
        local.value = local.value.copy(query = query)
        filter.value = filter.value.copy(query = query)
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
        local.value = local.value.copy(searchQuery = query)
    }

    /**
     * Tunes a channel and narrows the zap list to its category, so "next
     * channel" from the player walks the group the user picked it from.
     */
    fun tuneInGroup(channel: ChannelEntity) {
        if (local.value.favoritesOnly || local.value.selectedGroup != channel.groupName) {
            setFavoritesOnly(false)
            setGroup(channel.groupName)
        }
        tune(channel)
    }

    /** Stops whatever is playing, live or on demand. */
    fun stop() {
        local.value = local.value.copy(
            currentChannel = null, catchup = null, vod = null,
            nowProgramme = null, nextProgramme = null, isBuffering = false,
        )
        request(null)
    }

    /** Reloads the active playlist (pull-to-refresh), then the guide in the background. */
    fun refreshActive() {
        if (local.value.refreshing) return
        val id = local.value.activePlaylistId.takeIf { it != 0L } ?: return
        local.value = local.value.copy(refreshing = true)
        viewModelScope.launch {
            val result = playlists.refresh(id)
            local.value = local.value.copy(
                refreshing = false,
                statusMessage = result.fold({ it.describe() }, { it.message ?: "Refresh failed" }),
            )
            if (result.isSuccess) SyncScheduler.refreshEpg(getApplication())
        }
    }

    fun openOverlay(overlay: Overlay) {
        local.value = local.value.copy(overlay = overlay, statusMessage = null)
    }

    fun closeOverlay() {
        local.value = local.value.copy(overlay = Overlay.NONE, query = "")
        filter.value = filter.value.copy(query = "")
    }

    fun setBuffering(buffering: Boolean) {
        if (local.value.isBuffering != buffering) {
            local.value = local.value.copy(isBuffering = buffering)
        }
    }

    fun reportError(message: String?) {
        local.value = local.value.copy(statusMessage = message, isBuffering = false)
    }

    fun clearStatus() {
        local.value = local.value.copy(statusMessage = null)
    }

    fun cycleAspect() {
        val next = AspectMode.entries[(local.value.aspectMode.ordinal + 1) % AspectMode.entries.size]
        local.value = local.value.copy(aspectMode = next, statusMessage = next.name)
        viewModelScope.launch { settings.setAspectMode(next) }
    }

    fun toggleFavorite() {
        val id = local.value.currentChannel?.id ?: return
        viewModelScope.launch { playlists.toggleFavorite(id) }
    }

    fun toggleFavorite(channelId: Long) {
        viewModelScope.launch { playlists.toggleFavorite(channelId) }
    }

    // --- Tuning -----------------------------------------------------------

    fun tune(channel: ChannelEntity) {
        local.value = local.value.copy(
            currentChannel = channel,
            catchup = null,
            vod = null,
            statusMessage = null,
        )
        request(liveItem(channel))
        viewModelScope.launch {
            playlists.recordWatch(channel.id)
            refreshNowNext()
        }
    }

    fun tune(channelId: Long) {
        viewModelScope.launch { playlists.channel(channelId)?.let { tune(it) } }
    }

    /** Channel up/down through the currently filtered list, wrapping at the ends. */
    fun zap(delta: Int) {
        val channels = local.value.channels
        if (channels.isEmpty()) return
        val current = local.value.currentIndex
        val next = if (current < 0) 0 else ((current + delta) % channels.size + channels.size) % channels.size
        tune(channels[next].channel)
    }

    /** Jumps back to the channel watched before this one. */
    fun recallPrevious() {
        viewModelScope.launch {
            val previous = settings.current().previousChannelId
            if (previous != 0L) playlists.channel(previous)?.let { tune(it) }
        }
    }

    // --- Direct channel-number entry --------------------------------------

    fun pushDigit(digit: Char) {
        val entry = (local.value.numberEntry + digit).takeLast(4)
        local.value = local.value.copy(numberEntry = entry, overlay = local.value.overlay)
        numberEntryJob?.cancel()
        numberEntryJob = viewModelScope.launch {
            // Wait for further digits the way a TV tuner does, then commit.
            delay(2_000)
            commitNumberEntry()
        }
    }

    fun commitNumberEntry() {
        val entry = local.value.numberEntry
        numberEntryJob?.cancel()
        local.value = local.value.copy(numberEntry = "")
        val number = entry.toIntOrNull() ?: return
        viewModelScope.launch {
            val channel = playlists.channelByNumber(local.value.activePlaylistId, number)
            if (channel != null) tune(channel) else reportError("No channel $number")
        }
    }

    // --- EPG --------------------------------------------------------------

    private suspend fun refreshNowNext() {
        val channel = local.value.currentChannel ?: return
        val at = System.currentTimeMillis()
        val current = epg.now(channel.tvgId)
        val next = epg.schedule(channel.tvgId, at, at + 12 * 60 * 60_000L)
            .firstOrNull { it.startMs > at }
        local.value = local.value.copy(nowProgramme = current, nextProgramme = next)
    }

    // --- Catch-up ---------------------------------------------------------

    /**
     * Resolves an archive URL for a past programme.
     * @return the URL to play, or null with a status message when unavailable
     */
    suspend fun catchupUrlFor(channel: ChannelEntity, programme: ProgrammeEntity): String? {
        if (channel.catchupType == CatchupType.NONE) {
            reportError("This channel has no catch-up")
            return null
        }
        val oldestAllowed = System.currentTimeMillis() -
            channel.catchupDays.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        if (programme.startMs < oldestAllowed) {
            reportError("Outside the ${channel.catchupDays}-day archive")
            return null
        }

        if (channel.catchupType == CatchupType.XTREAM_CODES) {
            val playlist = local.value.playlists.firstOrNull { it.id == channel.playlistId }
            if (playlist == null || playlist.kind != SourceKind.XTREAM) {
                reportError("Catch-up needs the Xtream login")
                return null
            }
            val streamId = channel.catchupSource ?: return null
            val minutes = ((programme.stopMs - programme.startMs) / 60_000).toInt().coerceAtLeast(1)
            return XtreamClient(
                context = getApplication(),
                baseUrl = playlist.source,
                username = playlist.username.orEmpty(),
                password = playlist.password.orEmpty(),
                userAgent = playlist.userAgent,
            ).timeshiftUrl(streamId, programme.startMs, minutes)
        }

        return CatchupUrls.build(channel, programme.startMs, programme.stopMs)
            ?: run { reportError("Could not build a catch-up URL"); null }
    }

    fun startCatchup(channel: ChannelEntity, programme: ProgrammeEntity) {
        viewModelScope.launch {
            val url = catchupUrlFor(channel, programme) ?: return@launch
            local.value = local.value.copy(
                currentChannel = channel,
                catchup = CatchupSession(programme, channel.id),
                vod = null,
                overlay = Overlay.NONE,
                statusMessage = null,
            )
            request(
                PlayItem(
                    key = "catchup:${channel.id}:${programme.startMs}",
                    url = url,
                    referrer = channel.referrer,
                    title = programme.title,
                    subtitle = channel.name,
                    artwork = channel.logo,
                    isLive = false,
                )
            )
        }
    }

    fun returnToLive() {
        val channel = local.value.currentChannel ?: return
        local.value = local.value.copy(catchup = null)
        request(liveItem(channel))
    }

    // --- Films and episodes ------------------------------------------------

    private fun resumableFrom(p: ProgressEntity?): Long =
        p?.takeIf { it.positionMs > 60_000 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95) }
            ?.positionMs ?: 0L

    private fun startVod(session: VodSession, item: PlayItem) {
        local.value = local.value.copy(
            currentChannel = null, catchup = null,
            nowProgramme = null, nextProgramme = null,
            vod = session, statusMessage = null,
        )
        request(item)
    }

    fun playMovie(movie: MovieEntity, fromStart: Boolean = false) {
        viewModelScope.launch {
            val key = VodRepository.movieKey(movie)
            val start = if (fromStart) 0 else resumableFrom(vodRepo.progress(key))
            val year = movie.year?.toString()
            startVod(
                VodSession("movie", movie.name, year, movie.poster, null, movieId = movie.id),
                PlayItem(
                    key = key, url = movie.url, referrer = null,
                    title = movie.name, subtitle = year, artwork = movie.poster,
                    isLive = false, startPositionMs = start,
                    progress = ProgressRef(key, movie.url, movie.playlistId, "movie", movie.name, year, movie.poster, null),
                ),
            )
        }
    }

    fun playEpisode(show: SeriesEntity, episode: EpisodeEntity, fromStart: Boolean = false) {
        viewModelScope.launch {
            val episodes = vodRepo.episodeList(show.id)
            val next = episodes.getOrNull(episodes.indexOfFirst { it.id == episode.id } + 1)
                ?.takeIf { episodes.any { e -> e.id == episode.id } }
            val key = VodRepository.episodeKey(show.playlistId, episode)
            val start = if (fromStart) 0 else resumableFrom(vodRepo.progress(key))
            val label = "S${episode.season} E${episode.episode}"
            val art = episode.still ?: show.cover
            startVod(
                VodSession(
                    kind = "episode",
                    title = episode.title,
                    subtitle = "${show.name} · $label",
                    poster = art,
                    plot = episode.plot ?: show.plot,
                    seriesId = show.id,
                    episodeId = episode.id,
                    next = next,
                ),
                PlayItem(
                    key = key, url = episode.url, referrer = null,
                    title = episode.title, subtitle = "${show.name} · $label", artwork = art,
                    isLive = false, startPositionMs = start,
                    progress = ProgressRef(key, episode.url, show.playlistId, "episode", show.name, "$label · ${episode.title}", show.cover, show.id),
                ),
            )
        }
    }

    /** Picks up a "continue watching" entry where it left off. */
    fun resume(p: ProgressEntity) {
        viewModelScope.launch {
            val seriesId = p.seriesId
            if (p.kind == "episode" && seriesId != null) {
                val show = vodRepo.show(seriesId)
                val episode = vodRepo.episodeList(seriesId).firstOrNull { it.url == p.url }
                if (show != null && episode != null) {
                    playEpisode(show, episode)
                    return@launch
                }
            }
            startVod(
                VodSession(p.kind, p.title, p.subtitle, p.poster, null, seriesId = seriesId),
                PlayItem(
                    key = p.key, url = p.url, referrer = null,
                    title = p.title, subtitle = p.subtitle, artwork = p.poster,
                    isLive = false, startPositionMs = resumableFrom(p),
                    progress = ProgressRef(p.key, p.url, p.playlistId, p.kind, p.title, p.subtitle, p.poster, seriesId),
                ),
            )
        }
    }

    fun playNextEpisode() {
        val session = local.value.vod ?: return
        val next = session.next ?: return
        val seriesId = session.seriesId ?: return
        viewModelScope.launch {
            vodRepo.show(seriesId)?.let { playEpisode(it, next, fromStart = true) }
        }
    }

    /** Called by the player host when a film or episode reaches its end. */
    fun onPlaybackEnded() {
        if (local.value.vod?.next != null) playNextEpisode()
    }

    fun saveProgress(ref: ProgressRef, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0) return
        viewModelScope.launch {
            vodRepo.saveProgress(
                ProgressEntity(
                    key = ref.key, playlistId = ref.playlistId, kind = ref.kind,
                    title = ref.title, subtitle = ref.subtitle, poster = ref.poster, url = ref.url,
                    positionMs = positionMs, durationMs = durationMs.coerceAtLeast(0),
                    updatedMs = System.currentTimeMillis(), seriesId = ref.seriesId,
                )
            )
        }
    }

    /** Records a finished title so it leaves "continue watching". */
    fun finishedProgress(ref: ProgressRef) {
        viewModelScope.launch {
            vodRepo.saveProgress(
                ProgressEntity(
                    key = ref.key, playlistId = ref.playlistId, kind = ref.kind,
                    title = ref.title, subtitle = ref.subtitle, poster = ref.poster, url = ref.url,
                    positionMs = 0, durationMs = 0,
                    updatedMs = System.currentTimeMillis(), seriesId = ref.seriesId,
                )
            )
        }
    }
}
