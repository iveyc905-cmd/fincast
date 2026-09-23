package app.ember.tv

import android.app.Application
import android.content.Context
import app.ember.tv.data.db.AppDatabase
import app.ember.tv.data.repo.EpgRepository
import app.ember.tv.data.repo.PlaylistRepository
import app.ember.tv.data.repo.SettingsRepository
import app.ember.tv.data.repo.VodRepository
import app.ember.tv.sync.SyncScheduler
import app.ember.tv.util.CrashLog

class EmberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
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
    lateinit var vod: VodRepository
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        val app = context.applicationContext
        database = AppDatabase.get(app)
        settings = SettingsRepository(app)
        playlists = PlaylistRepository(app, database, settings)
        epg = EpgRepository(app, database)
        vod = VodRepository(app, database)
    }
}
