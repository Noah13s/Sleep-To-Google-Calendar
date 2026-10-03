package com.noah.sleeptocalendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as Calendars
import android.provider.CalendarContract.Events as Events
import android.util.Base64
import org.json.JSONObject
import java.time.Instant

data class CalendarChoice(val id: Long, val name: String, val account: String, val accountType: String) {
    val label: String get() = "$name · $account${if (accountType == "com.google") " (Google)" else ""}"
    val identity: String get() = "$id\u0000$account\u0000$accountType"
}
data class SyncCounts(val read: Int, val created: Int, val updated: Int, val unchanged: Int) {
    override fun toString() = "$read sessions · $created added · $updated updated · $unchanged unchanged"
}

/** Marker travels with the Google event, recovering identity after reinstall or a crash.
 * CalendarContract sync-only columns are intentionally avoided by this ordinary app. */
object EventMarker {
    const val PREFIX = "[SleepToCalendar:v1:"
    fun encode(s: SleepSession): String {
        val json = JSONObject().put("key", s.key).put("record", s.recordId).put("source", s.source)
        return PREFIX + Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE) + "]"
    }
    fun decode(description: String): Triple<String, String, String>? {
        val line = description.lineSequence().lastOrNull()?.takeIf { it.startsWith(PREFIX) && it.endsWith("]") } ?: return null
        return runCatching {
            val json = JSONObject(String(Base64.decode(line.substring(PREFIX.length, line.length - 1), Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
            Triple(json.getString("key"), json.getString("record"), json.getString("source"))
                .takeIf { it.first.matches(Regex("[a-f0-9]{64}")) && it.second.isNotBlank() && it.third.isNotBlank() }
        }.getOrNull()
    }
}

class CalendarGateway(private val resolver: ContentResolver) {
    fun calendars(): List<CalendarChoice> {
        return resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(CalendarChoice(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty()))
            }
        } ?: error("Android calendar provider did not respond. Try again.")
    }

    fun managed(calendarId: Long): List<ManagedEvent> {
        return resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.TITLE, Events.DESCRIPTION),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?",
            arrayOf(calendarId.toString(), "%${EventMarker.PREFIX}%"), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val description = cursor.getString(4).orEmpty()
                    val marker = EventMarker.decode(description) ?: continue
                    if (cursor.isNull(1) || cursor.isNull(2)) continue
                    add(ManagedEvent(cursor.getLong(0), marker.first, marker.second, marker.third,
                        Instant.ofEpochMilli(cursor.getLong(1)), Instant.ofEpochMilli(cursor.getLong(2)), cursor.getString(3).orEmpty(), description))
                }
            }
        } ?: error("Android calendar provider did not respond. Try again.")
    }

    fun sync(calendarId: Long, sessions: List<SleepSession>): SyncCounts {
        val plans = SyncPlanner.plan(sessions, managed(calendarId))
        var created = 0
        var updated = 0
        var unchanged = 0
        val operations = arrayListOf<ContentProviderOperation>()
        plans.forEach { (session, old) ->
            val title = "Sleep · ${durationText(java.time.Duration.between(session.start, session.end).seconds)}"
            val description = sleepDetails(session) + "\n\n" + EventMarker.encode(session)
            if (old != null && old.start == session.start && old.end == session.end && old.title == title && old.description == description) {
                unchanged++
                return@forEach
            }
            val values = ContentValues().apply {
                put(Events.DTSTART, session.start.toEpochMilli()); put(Events.DTEND, session.end.toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC"); put(Events.TITLE, title); put(Events.DESCRIPTION, description)
            }
            if (old == null) {
                created++
                values.put(Events.CALENDAR_ID, calendarId)
                values.put(Events.ALL_DAY, 0)
                values.put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
                values.put(Events.HAS_ALARM, 0)
                operations += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values).build()
            } else {
                updated++
                operations += ContentProviderOperation.newUpdate(Events.CONTENT_URI).withValues(values)
                    .withSelection("${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(old.id.toString(), calendarId.toString()))
                    .withExpectedCount(1).build()
            }
        }
        // Small batches prevent Binder transaction limits with long stage notes. A retry is
        // safe even after some batches succeed: it discovers the persisted event markers.
        operations.chunked(100).forEach { resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(it)) }
        return SyncCounts(plans.size, created, updated, unchanged)
    }
}

