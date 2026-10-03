package com.noah.sleeptocalendar

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Opt-in device harness. Finish instrumentation and put the app off-screen before
 * the delayed normal WorkManager job runs; inspect sync status afterward. */
@RunWith(AndroidJUnit4::class)
class BackgroundValidationTest {
    @Test fun scheduleDelayedRealBackgroundSync() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyBackground") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = SyncRepository(context)
        assertTrue("Enable automatic sync in the app first", repository.settings.automatic)
        assertTrue("Grant background health access first", repository.backgroundPermission())
        assertTrue("Select a calendar first", repository.settings.calendarId >= 0)
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("sleep-calendar-device-verification", ExistingWorkPolicy.REPLACE, request).await()
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nDelayed SyncWorker scheduled. Leave the app off-screen for at least 10 seconds, then check that its last successful sync advanced and event IDs remain unchanged.\n")
        })
    }
}
