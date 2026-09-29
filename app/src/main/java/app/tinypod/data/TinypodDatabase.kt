package app.tinypod.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Folder::class, Podcast::class, Episode::class, QueueItem::class], version = 1)
abstract class TinypodDatabase : RoomDatabase() {
  abstract fun folderDao(): FolderDao

  abstract fun podcastDao(): PodcastDao

  abstract fun episodeDao(): EpisodeDao

  abstract fun queueDao(): QueueDao

  companion object {
    fun create(context: Context): TinypodDatabase =
      Room.databaseBuilder(context, TinypodDatabase::class.java, "tinypod.db").build()
  }
}
