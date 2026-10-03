package com.noah.sleeptocalendar

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.Duration

class SyncPlannerTest {
    private val start = Instant.parse("2026-10-02T21:00:00Z")
    private fun session(id: String = "night", offset: Long = 0, hours: Long = 8, source: String = "mi", client: String? = null) =
        SleepSession(id, source, client, start.plusSeconds(offset), start.plusSeconds(offset + hours * 3600), null, null, emptyList(), start)
    private fun event(s: SleepSession, id: Long = 10) = ManagedEvent(id, s.key, s.recordId, s.source, s.start, s.end, "Sleep", "")

    @Test fun repeatedSyncReusesIdentity() {
        val s = session()
        assertEquals(10L, SyncPlanner.plan(listOf(s), listOf(event(s))).single().existing?.id)
    }
    @Test fun correctedTimesKeepEventEvenWithoutOverlap() {
        val s = session()
        assertEquals(10L, SyncPlanner.plan(listOf(s.copy(start = start.plusSeconds(86400), end = start.plusSeconds(90000))), listOf(event(s))).single().existing?.id)
    }
    @Test fun stableClientIdSurvivesRecordReplacement() {
        val s = session(client = "vendor-123")
        assertEquals(10L, SyncPlanner.plan(listOf(s.copy(recordId = "new")), listOf(event(s))).single().existing?.id)
    }
    @Test fun addingClientIdStillKeepsSameHealthRecord() {
        val s = session()
        assertEquals(10L, SyncPlanner.plan(listOf(s.copy(clientId = "new")), listOf(event(s))).single().existing?.id)
    }
    @Test fun unambiguousReplacementReusesEvent() {
        val s = session()
        assertEquals(10L, SyncPlanner.plan(listOf(s.copy(recordId = "replacement", start = start.plusSeconds(600))), listOf(event(s))).single().existing?.id)
    }
    @Test fun ambiguousReplacementNeverMergesSessions() {
        val s = session()
        val plans = SyncPlanner.plan(listOf(s.copy(recordId = "a"), s.copy(recordId = "b")), listOf(event(s)))
        assertTrue(plans.all { it.existing == null })
    }
    @Test fun multipleOldCandidatesNeverGuesses() {
        val s = session()
        assertNull(SyncPlanner.plan(listOf(s.copy(recordId = "new")), listOf(event(s), event(s.copy(recordId = "old2"), 11))).single().existing)
    }
    @Test fun napAndNightRemainSeparate() {
        val s = session()
        val nap = session(id = "nap", offset = 16 * 3600, hours = 1)
        val plans = SyncPlanner.plan(listOf(s, nap), listOf(event(s)))
        assertEquals(2, plans.size)
        assertNull(plans.last().existing)
    }
    @Test fun currentOverlappingRecordsRemainSeparate() {
        val s = session()
        val plans = SyncPlanner.plan(listOf(s, s.copy(recordId = "other")), listOf(event(s)))
        assertEquals(1, plans.count { it.existing == null })
    }
    @Test fun differentSourcesNeverMatch() {
        val s = session()
        assertNull(SyncPlanner.plan(listOf(s.copy(source = "other")), listOf(event(s))).single().existing)
    }
    @Test fun newestRevisionWinsForRepeatedClientId() {
        val s = session(client = "stable")
        val newer = s.copy(recordId = "new", modified = start.plusSeconds(10))
        assertEquals(newer, SyncPlanner.plan(listOf(s, newer), emptyList()).single().session)
    }
    @Test fun stagesAndUnstagedDurationAreHonest() {
        val s = session(hours = 2).copy(stages = listOf(
            SleepStage(start, start.plusSeconds(3600), "Deep"),
            SleepStage(start.plusSeconds(3600), start.plusSeconds(5400), "Awake")))
        val description = sleepDetails(s)
        assertTrue(description.contains("Recorded asleep: 1h 0m"))
        assertTrue(description.contains("Awake: 0h 30m"))
        assertTrue(description.contains("Unstaged: 0h 30m"))
    }
    @Test fun noStagesDoesNotInventSleepDuration() {
        val details = sleepDetails(session())
        assertTrue(details.contains("not supplied"))
        assertFalse(details.contains("Recorded asleep"))
    }
    @Test fun elapsedDurationHandlesDaylightSaving() {
        val s = session().copy(start = Instant.parse("2026-10-24T21:00:00Z"), end = Instant.parse("2026-10-25T07:00:00Z"))
        assertTrue(sleepDetails(s).contains("10h 0m"))
        assertEquals(Duration.ofHours(10), Duration.between(s.start, s.end))
    }
}
