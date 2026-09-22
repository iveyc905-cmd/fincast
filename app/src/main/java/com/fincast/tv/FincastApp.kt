package com.fincast.tv

import android.app.Application
import android.content.Context
import com.fincast.tv.data.db.AppDatabase
import com.fincast.tv.data.repo.EpgRepository
import com.fincast.tv.data.repo.PlaylistRepository
import com.fincast.tv.data.repo.SettingsRepository
import com.fincast.tv.sync.SyncScheduler

class FincastApp : Application() {
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
