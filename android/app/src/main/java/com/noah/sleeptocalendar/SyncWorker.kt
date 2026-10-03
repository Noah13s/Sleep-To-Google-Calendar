package com.noah.sleeptocalendar

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = SyncRepository(applicationContext)
        if (!repository.settings.automatic) return Result.success()
        return try {
            repository.sync(background = true)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (setup: SetupRequired) {
            repository.settings.status = setup.message.orEmpty()
            Result.success() // permission failures need user action, not retry storms
        } catch (denied: SecurityException) {
            repository.settings.status = "Access revoked. Check Sleep and calendar permissions."
            Result.success()
        } catch (error: Exception) {
            repository.settings.status = "Automatic sync could not complete; it will retry. Open the app for manual sync."
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

object SyncSchedule {
    const val NAME = "sleep-calendar-hourly"
    fun update(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (enabled) manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build())
        else manager.cancelUniqueWork(NAME)
    }
}
