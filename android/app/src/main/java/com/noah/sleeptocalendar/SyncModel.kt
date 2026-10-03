package com.noah.sleeptocalendar

import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

data class SleepStage(val start: Instant, val end: Instant, val label: String)
data class SleepSession(
    val recordId: String, val source: String, val clientId: String?,
    val start: Instant, val end: Instant, val title: String?, val notes: String?,
    val stages: List<SleepStage>, val modified: Instant,
) {
    val key: String get() = hash("$source\u0000${clientId?.takeIf { it.isNotBlank() } ?: recordId}")
}
data class ManagedEvent(
    val id: Long, val key: String, val recordId: String, val source: String,
    val start: Instant, val end: Instant, val title: String, val description: String,
)
data class PlannedEvent(val session: SleepSession, val existing: ManagedEvent?)

/** Exact identity first. A replaced provider ID is matched only if both sides have one
 * unambiguous, strongly overlapping candidate. Never collapse current sessions/naps. */
object SyncPlanner {
    fun plan(sessions: List<SleepSession>, events: List<ManagedEvent>): List<PlannedEvent> {
        val unique = sessions.groupBy { it.key }.values.map { it.maxBy { s -> s.modified } }
            .sortedBy { it.start }
        val currentIds = unique.map { it.recordId }.toSet()
        val currentKeys = unique.map { it.key }.toSet()
        val unmatchedEvents = events.filter { it.key !in currentKeys && it.recordId !in currentIds }
        val unmatchedSessions = unique.filter { s -> events.none { it.key == s.key } }
        return unique.map { s ->
            val exact = events.filter { it.key == s.key || (it.source == s.source && it.recordId == s.recordId) }.minByOrNull { it.id }
            val candidates = unmatchedEvents.filter { replacement(s, it) }
            val fallback = candidates.singleOrNull()?.takeIf { e ->
                unmatchedSessions.count { replacement(it, e) } == 1
            }
            PlannedEvent(s, exact ?: fallback)
        }
    }

    private fun replacement(s: SleepSession, e: ManagedEvent): Boolean {
        if (s.source != e.source) return false
        val overlap = Duration.between(maxOf(s.start, e.start), minOf(s.end, e.end)).seconds
        val longest = maxOf(Duration.between(s.start, s.end).seconds, Duration.between(e.start, e.end).seconds)
        return longest > 0 && overlap.toDouble() / longest >= 0.8 &&
            Duration.between(s.start, e.start).abs() <= Duration.ofMinutes(90)
    }
}

fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun durationText(seconds: Long): String = "${seconds / 3600}h ${(seconds % 3600) / 60}m"

fun sleepDetails(s: SleepSession): String = buildString {
    val total = Duration.between(s.start, s.end).seconds
    appendLine("Sleep session: ${durationText(total)}")
    val stages = s.stages.filter { it.end > it.start }
    if (stages.isEmpty()) appendLine("Sleep stages: not supplied by the source app.")
    else {
        val totals = stages.groupBy { it.label }.mapValues { (_, items) ->
            items.sumOf { Duration.between(maxOf(it.start, s.start), minOf(it.end, s.end)).seconds.coerceAtLeast(0) }
        }
        val asleep = totals.filterKeys { it in setOf("Sleeping", "Light", "Deep", "REM") }.values.sum()
        if (asleep > 0) appendLine("Recorded asleep: ${durationText(asleep)}")
        totals.toSortedMap().forEach { (label, seconds) -> appendLine("$label: ${durationText(seconds)}") }
        val covered = totals.values.sum()
        if (covered < total) appendLine("Unstaged: ${durationText(total - covered)}")
    }
    s.title?.takeIf { it.isNotBlank() }?.let { appendLine("Source title: $it") }
    s.notes?.takeIf { it.isNotBlank() }?.let { appendLine("Notes: $it") }
    appendLine("Source app: ${s.source}")
    append("Synced from Android Health Connect by Sleep to Calendar.")
}
