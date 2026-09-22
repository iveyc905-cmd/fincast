package com.fincast.tv.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fincast.tv.Graph
import java.util.concurrent.TimeUnit

class PlaylistSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val results = Graph.playlists.refreshAll()
        // A provider being briefly unreachable is normal; retry rather than fail
        // outright, but only if nothing at all succeeded.
        return when {
            results.isEmpty() -> Result.success()
            results.values.any { it.isSuccess } -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}

class EpgSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Graph.init(applicationContext)
        val force = inputData.getBoolean(KEY_FORCE, false)
        return Graph.epg.refreshAll(force).fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
        )
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}

object SyncScheduler {

    private const val PLAYLIST_WORK = "playlist-sync"
    private const val EPG_WORK = "epg-sync"

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodic(context: Context) {
        val manager = WorkManager.getInstance(context)

        manager.enqueueUniquePeriodicWork(
            PLAYLIST_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PlaylistSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .build(),
        )

        manager.enqueueUniquePeriodicWork(
            EPG_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EpgSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .build(),
        )
    }

    /** Fired from the UI when the user picks "refresh now". */
    fun refreshNow(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueue(
            OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setConstraints(networkConstraints)
                .build()
        )
        manager.enqueue(
            OneTimeWorkRequestBuilder<EpgSyncWorker>()
                .setConstraints(networkConstraints)
                .setInputData(androidx.work.workDataOf(EpgSyncWorker.KEY_FORCE to true))
                .build()
        )
    }
}
