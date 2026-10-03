package com.noah.sleeptocalendar

import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.testing.populatedWithTestValues
import androidx.health.connect.client.testing.stubs.MutableStub
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HealthGatewayTest {
    private val now = Instant.parse("2026-10-03T10:00:00Z")
    private fun record(id: String, end: Instant = now.minusSeconds(3600)) = SleepSessionRecord(
        startTime = end.minusSeconds(28800), endTime = end, startZoneOffset = null, endZoneOffset = null,
        metadata = Metadata.manualEntry().populatedWithTestValues(id = id, dataOrigin = DataOrigin("mi"), lastModifiedTime = now),
    )

    @Test fun readsEveryPageAndTreatsEmptyTokenAsFinished() = runTest {
        val client = FakeHealthConnectClient()
        val tokens = mutableListOf<String?>()
        client.overrides.readRecords = MutableStub { request ->
            tokens += request.pageToken
            assertEquals(setOf(DataOrigin("mi")), request.dataOriginFilter)
            if (request.pageToken == null) ReadRecordsResponse(listOf(record("night")), "page2")
            else ReadRecordsResponse(listOf(record("nap")), "")
        }
        assertEquals(listOf("night", "nap"), HealthGateway(client).recent("mi", now).map { it.recordId })
        assertEquals(listOf(null, "page2"), tokens)
    }

    @Test fun newTokenIsAcquiredBeforeInitialRead() = runTest {
        val client = FakeHealthConnectClient()
        val calls = mutableListOf<String>()
        client.overrides.getChangesToken = MutableStub { request ->
            assertEquals(setOf(DataOrigin("mi")), request.dataOriginFilters)
            calls += "token"; "new-token"
        }
        client.overrides.readRecords = MutableStub { calls += "read"; ReadRecordsResponse(listOf(record("night")), null) }
        val snapshot = HealthGateway(client).snapshot("mi", null, now)
        assertEquals(listOf("token", "read"), calls)
        assertEquals("new-token", snapshot.nextToken)
    }

    @Test fun paginatedChangesIncludeEditsOutsideRecentWindow() = runTest {
        val client = FakeHealthConnectClient()
        val tokens = mutableListOf<String>()
        client.overrides.getChanges = MutableStub { token ->
            tokens += token
            if (token == "saved") ChangesResponse(listOf(UpsertionChange(record("old", now.minusSeconds(60L * 86400)))), "next", true, false)
            else ChangesResponse(listOf(UpsertionChange(record("other-old", now.minusSeconds(50L * 86400)))), "done", false, false)
        }
        client.overrides.readRecords = MutableStub { ReadRecordsResponse(listOf(record("recent")), null) }
        val snapshot = HealthGateway(client).snapshot("mi", "saved", now)
        assertEquals(setOf("old", "other-old", "recent"), snapshot.sessions.map { it.recordId }.toSet())
        assertEquals(listOf("saved", "next"), tokens)
        assertEquals("done", snapshot.nextToken)
    }

    @Test fun expiredTokenRestartsWithoutUsingPartialChanges() = runTest {
        val client = FakeHealthConnectClient()
        client.overrides.getChanges = MutableStub { token ->
            if (token == "saved") ChangesResponse(listOf(UpsertionChange(record("stale"))), "expired", true, false)
            else ChangesResponse(emptyList(), "ignored", false, true)
        }
        client.overrides.getChangesToken = MutableStub { "fresh" }
        client.overrides.readRecords = MutableStub { ReadRecordsResponse(listOf(record("recent")), null) }
        val snapshot = HealthGateway(client).snapshot("mi", "saved", now)
        assertEquals(listOf("recent"), snapshot.sessions.map { it.recordId })
        assertEquals("fresh", snapshot.nextToken)
    }

    @Test fun deletionRemovesPriorChangeUpsert() = runTest {
        val client = FakeHealthConnectClient()
        client.overrides.getChanges = MutableStub {
            ChangesResponse(listOf(UpsertionChange(record("deleted")), DeletionChange("deleted")), "done", false, false)
        }
        client.overrides.readRecords = MutableStub { ReadRecordsResponse(emptyList<SleepSessionRecord>(), null) }
        assertTrue(HealthGateway(client).snapshot("mi", "saved", now).sessions.isEmpty())
    }

    @Test fun currentSnapshotWinsOverOldChange() = runTest {
        val client = FakeHealthConnectClient()
        client.overrides.getChanges = MutableStub { ChangesResponse(listOf(UpsertionChange(record("same", now.minusSeconds(7200)))), "done", false, false) }
        client.overrides.readRecords = MutableStub { ReadRecordsResponse(listOf(record("same")), null) }
        assertEquals(now.minusSeconds(3600), HealthGateway(client).snapshot("mi", "saved", now).sessions.single().end)
    }

    @Test fun accessRevocationPropagatesWithoutReturningAToken() = runTest {
        val client = FakeHealthConnectClient()
        client.overrides.readRecords = MutableStub { throw SecurityException("revoked") }
        var denied = false
        try { HealthGateway(client).recent("mi", now) } catch (e: SecurityException) { denied = true }
        assertTrue(denied)
    }
}
