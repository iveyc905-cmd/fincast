package com.fincast.tv.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fincast.tv.Graph
import com.fincast.tv.data.db.ChannelEntity
import com.fincast.tv.data.db.ChannelWithNow
import com.fincast.tv.data.db.PlaylistEntity
import com.fincast.tv.data.db.ProgrammeEntity
import com.fincast.tv.data.model.CatchupType
import com.fincast.tv.data.model.SourceKind
import com.fincast.tv.data.net.XtreamClient
import com.fincast.tv.data.repo.AspectMode
import com.fincast.tv.util.CatchupUrls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Overlay { NONE, CHANNELS, GUIDE, INFO, TRACKS, MENU }

/** A programme being replayed from the provider's archive rather than live. */
data class CatchupSession(val programme: ProgrammeEntity, val channelId: Long)

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
) {
    val currentIndex: Int
        get() = channels.indexOfFirst { it.channel.id == currentChannel?.id }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val playlists = Graph.playlists
    private val epg = Graph.epg
    private val settings = Graph.settings

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
            statusMessage = null,
        )
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
                overlay = Overlay.NONE,
                statusMessage = null,
            )
            pendingUrl.value = url to channel.referrer
        }
    }

    /** The URL the composable should hand to the player, if it differs from live. */
    val pendingUrl = MutableStateFlow<Pair<String, String?>?>(null)

    fun consumedPendingUrl() {
        pendingUrl.value = null
    }

    fun returnToLive() {
        val channel = local.value.currentChannel ?: return
        local.value = local.value.copy(catchup = null)
        pendingUrl.value = channel.url to channel.referrer
    }
}
