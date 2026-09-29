package app.tinypod.feed

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tinypod.TinypodApp
import java.util.concurrent.TimeUnit

/** Periodically re-fetches all feeds in the background. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    // Individual feed failures are logged and skipped; the next run simply tries again.
    (applicationContext as TinypodApp).repository.refreshAll(olderThanMs = TimeUnit.HOURS.toMillis(1))
    return Result.success()
  }

  companion object {
    fun schedule(context: Context) {
      val request =
        PeriodicWorkRequestBuilder<RefreshWorker>(4, TimeUnit.HOURS)
          .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
          .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork("feed-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
