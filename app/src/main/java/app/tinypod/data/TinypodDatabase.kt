package app.tinypod.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [Folder::class, Podcast::class, Episode::class, QueueItem::class],
  version = 5,
  autoMigrations = [
    AutoMigration(from = 1, to = 2), // Episode.durationMeasured
    AutoMigration(from = 2, to = 3), // Episode.downloadId
    AutoMigration(from = 4, to = 5), // Podcast.link, Episode.link
  ],
)
abstract class TinypodDatabase : RoomDatabase() {
  abstract fun folderDao(): FolderDao

  abstract fun podcastDao(): PodcastDao

  abstract fun episodeDao(): EpisodeDao

  abstract fun queueDao(): QueueDao

  companion object {
    fun create(context: Context): TinypodDatabase =
      Room.databaseBuilder(context, TinypodDatabase::class.java, "tinypod.db").addMigrations(MIGRATION_3_4).build()

    /** No schema change: applies the "newer than the latest finished episode" rule to existing data. */
    val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """UPDATE Podcast SET newSince = MAX(newSince,
                 COALESCE((SELECT MAX(publishedAt) + 1 FROM Episode WHERE podcastId = Podcast.id AND isPlayed = 1), 0))"""
          )
        }
      }
  }
}
