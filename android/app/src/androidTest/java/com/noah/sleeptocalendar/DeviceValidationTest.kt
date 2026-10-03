package com.noah.sleeptocalendar

import android.os.Bundle
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only verification of the real configured pipeline. No private sleep
 * dates, stage values, account names or change tokens are written to the test report. */
@RunWith(AndroidJUnit4::class)
class DeviceValidationTest {
    @Test fun verifyConfiguredSleepAndGoogleCalendar() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRealData") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = SyncRepository(context)
        assertTrue("Calendar access required", repository.calendarPermission())
        assertTrue("Sleep access required", repository.sleepPermission())
        assertTrue("Select a calendar in the app first", repository.settings.calendarId >= 0)
        val sessions = HealthGateway(repository.client()).recent(repository.settings.source)
        assertTrue("Sync Mi Fitness and verify Health Connect contains Sleep first", sessions.isNotEmpty())
        val events = CalendarGateway(context.contentResolver).managed(repository.settings.calendarId)
        val selectedEvents = sessions.map { session ->
            val matching = events.filter { it.key == session.key }
            assertEquals("Exactly one managed event per current session", 1, matching.size)
            matching.single().also {
                assertEquals(session.start, it.start)
                assertEquals(session.end, it.end)
                assertTrue(it.description.startsWith(sleepDetails(session)))
            }
        }
        var uploaded = 0
        context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID, Events.DIRTY),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(repository.settings.calendarId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (selectedEvents.any { it.id == cursor.getLong(0) } && !cursor.isNull(1) && cursor.getString(1).isNotBlank() && cursor.getInt(2) == 0) uploaded++
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nReal device: ${sessions.size} Health Connect sleep sessions; ${selectedEvents.size} matching calendar events; ${sessions.count { it.stages.isNotEmpty() }} with stages; $uploaded uploaded by calendar account sync. Background access: ${repository.backgroundPermission()}.\n")
        })
        assertEquals("Calendar account sync has not uploaded all managed events yet", selectedEvents.size, uploaded)
    }
}
