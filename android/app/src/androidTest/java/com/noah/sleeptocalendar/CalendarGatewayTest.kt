package com.noah.sleeptocalendar

import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Uses an isolated LOCAL calendar; never reads/writes the user's selected Google calendar. */
@RunWith(AndroidJUnit4::class)
class CalendarGatewayTest {
    @Test fun realProviderInsertsUpdatesRecoversAndKeepsNaps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "SleepToCalendar test ${UUID.randomUUID()}"
        val calendarUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, name)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, name)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, name)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, name)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff326b68.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Europe/Paris")
            put(CalendarContract.Calendars.VISIBLE, 0)
            put(CalendarContract.Calendars.SYNC_EVENTS, 0)
        }
        val calendar = requireNotNull(resolver.insert(calendarUri, values))
        val id = ContentUris.parseId(calendar)
        try {
            val gateway = CalendarGateway(resolver)
            assertTrue(gateway.calendars().any { it.id == id })
            val start = Instant.parse("2026-10-02T21:00:00Z")
            val night = SleepSession("test-night", "test-source", "night", start, start.plusSeconds(28800), null, null, emptyList(), start)
            val nap = night.copy(recordId = "test-nap", clientId = "nap", start = start.plusSeconds(57600), end = start.plusSeconds(59400))
            assertEquals(2, gateway.sync(id, listOf(night, nap)).created)
            val first = gateway.managed(id)
            assertEquals(2, first.size)
            assertEquals(2, CalendarGateway(resolver).sync(id, listOf(night, nap)).unchanged)
            val changed = night.copy(end = night.end.plusSeconds(1800), stages = listOf(SleepStage(start, night.end, "Deep")))
            assertEquals(1, gateway.sync(id, listOf(changed, nap)).updated)
            assertEquals(first.map { it.id }.sorted(), gateway.managed(id).map { it.id }.sorted())
            assertEquals(1, gateway.sync(id, listOf(changed.copy(recordId = "rewritten"), nap)).updated)
            assertEquals(2, gateway.managed(id).size)
            assertNull(EventMarker.decode("Ordinary event with Sleep in title"))
            assertNull(EventMarker.decode(EventMarker.PREFIX + "invalid]"))
            // A deleted source session retains the user's existing calendar event.
            assertEquals(0, gateway.sync(id, listOf(nap)).created)
            assertEquals(2, gateway.managed(id).size)
        } finally {
            // Exact URI of the disposable test calendar, using its original sync account.
            resolver.delete(calendarUri, "${CalendarContract.Calendars._ID} = ?", arrayOf(id.toString()))
        }
    }
}
