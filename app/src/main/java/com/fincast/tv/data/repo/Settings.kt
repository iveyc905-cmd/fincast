package com.fincast.tv.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fincast.tv.data.net.DEFAULT_USER_AGENT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

enum class AspectMode { FIT, FILL, ZOOM, STRETCH }

data class AppSettings(
    val activePlaylistId: Long = 0,
    val lastChannelId: Long = 0,
    val previousChannelId: Long = 0,
    val userAgent: String = DEFAULT_USER_AGENT,
    val bufferSeconds: Int = 30,
    val resumeOnStart: Boolean = true,
    val showChannelNumbers: Boolean = true,
    val epgRefreshHours: Int = 12,
    val playlistRefreshHours: Int = 24,
    val aspectMode: AspectMode = AspectMode.FIT,
    val guideHoursVisible: Int = 3,
    val tunnelingEnabled: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val activePlaylist = longPreferencesKey("active_playlist")
        val lastChannel = longPreferencesKey("last_channel")
        val previousChannel = longPreferencesKey("previous_channel")
        val userAgent = stringPreferencesKey("user_agent")
        val bufferSeconds = intPreferencesKey("buffer_seconds")
        val resumeOnStart = booleanPreferencesKey("resume_on_start")
        val showChannelNumbers = booleanPreferencesKey("show_channel_numbers")
        val epgRefreshHours = intPreferencesKey("epg_refresh_hours")
        val playlistRefreshHours = intPreferencesKey("playlist_refresh_hours")
        val aspectMode = stringPreferencesKey("aspect_mode")
        val guideHours = intPreferencesKey("guide_hours")
        val tunneling = booleanPreferencesKey("tunneling")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            activePlaylistId = p[Keys.activePlaylist] ?: 0,
            lastChannelId = p[Keys.lastChannel] ?: 0,
            previousChannelId = p[Keys.previousChannel] ?: 0,
            userAgent = p[Keys.userAgent] ?: DEFAULT_USER_AGENT,
            bufferSeconds = p[Keys.bufferSeconds] ?: 30,
            resumeOnStart = p[Keys.resumeOnStart] ?: true,
            showChannelNumbers = p[Keys.showChannelNumbers] ?: true,
            epgRefreshHours = p[Keys.epgRefreshHours] ?: 12,
            playlistRefreshHours = p[Keys.playlistRefreshHours] ?: 24,
            aspectMode = runCatching { AspectMode.valueOf(p[Keys.aspectMode] ?: "FIT") }
                .getOrDefault(AspectMode.FIT),
            guideHoursVisible = p[Keys.guideHours] ?: 3,
            tunnelingEnabled = p[Keys.tunneling] ?: true,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setActivePlaylist(id: Long) = edit { it[Keys.activePlaylist] = id }

    /** Records the new channel and rotates the old one into "previous" for recall. */
    suspend fun setLastChannel(id: Long) = edit { prefs ->
        val current = prefs[Keys.lastChannel] ?: 0
        if (current != id && current != 0L) prefs[Keys.previousChannel] = current
        prefs[Keys.lastChannel] = id
    }

    suspend fun setUserAgent(value: String) = edit { it[Keys.userAgent] = value }
    suspend fun setBufferSeconds(value: Int) = edit { it[Keys.bufferSeconds] = value }
    suspend fun setResumeOnStart(value: Boolean) = edit { it[Keys.resumeOnStart] = value }
    suspend fun setShowChannelNumbers(value: Boolean) = edit { it[Keys.showChannelNumbers] = value }
    suspend fun setEpgRefreshHours(value: Int) = edit { it[Keys.epgRefreshHours] = value }
    suspend fun setPlaylistRefreshHours(value: Int) = edit { it[Keys.playlistRefreshHours] = value }
    suspend fun setAspectMode(mode: AspectMode) = edit { it[Keys.aspectMode] = mode.name }
    suspend fun setGuideHours(value: Int) = edit { it[Keys.guideHours] = value }
    suspend fun setTunneling(value: Boolean) = edit { it[Keys.tunneling] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
