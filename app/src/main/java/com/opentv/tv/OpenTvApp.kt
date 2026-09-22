package com.opentv.tv

import android.app.Application
import android.content.Context
import com.opentv.tv.data.db.AppDatabase
import com.opentv.tv.data.repo.EpgRepository
import com.opentv.tv.data.repo.PlaylistRepository
import com.opentv.tv.data.repo.SettingsRepository
import com.opentv.tv.sync.SyncScheduler

class OpenTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        SyncScheduler.schedulePeriodic(this)
    }
}

/**
 * Hand-rolled service locator. The graph is five objects deep, so a DI framework
 * would cost more in build time and indirection than it saves.
 */
object Graph {
    lateinit var database: AppDatabase
        private set
    lateinit var playlists: PlaylistRepository
        private set
    lateinit var epg: EpgRepository
        private set
    lateinit var settings: SettingsRepository
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        val app = context.applicationContext
        database = AppDatabase.get(app)
        settings = SettingsRepository(app)
        playlists = PlaylistRepository(app, database, settings)
        epg = EpgRepository(app, database)
    }
}
