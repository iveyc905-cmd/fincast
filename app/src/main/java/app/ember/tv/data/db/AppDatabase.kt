package app.ember.tv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.ember.tv.data.model.CatchupType
import app.ember.tv.data.model.SourceKind

class Converters {
    @TypeConverter fun catchupToString(v: CatchupType): String = v.name
    @TypeConverter fun stringToCatchup(v: String): CatchupType =
        runCatching { CatchupType.valueOf(v) }.getOrDefault(CatchupType.NONE)

    @TypeConverter fun kindToString(v: SourceKind): String = v.name
    @TypeConverter fun stringToKind(v: String): SourceKind =
        runCatching { SourceKind.valueOf(v) }.getOrDefault(SourceKind.M3U_URL)
}

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        ProgrammeEntity::class,
        WatchStateEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        ProgressEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun favorites(): FavoriteDao
    abstract fun programmes(): ProgrammeDao
    abstract fun watchState(): WatchStateDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun progress(): ProgressDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ember.db"
            )
                // Channels and EPG are both re-downloadable caches, so a schema
                // bump is cheaper to handle by rebuilding than by migrating.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
