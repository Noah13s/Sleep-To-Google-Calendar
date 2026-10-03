package com.noah.sleeptocalendar

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

data class SleepSnapshot(val sessions: List<SleepSession>, val nextToken: String)

class HealthGateway(private val client: HealthConnectClient) {
    suspend fun recent(source: String, now: Instant = Instant.now()): List<SleepSession> {
        val sessions = mutableListOf<SleepSession>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(now.minus(Duration.ofDays(29)), now),
                dataOriginFilter = if (source.isBlank()) emptySet() else setOf(DataOrigin(source)),
                pageToken = page,
            ))
            sessions += response.records.map { it.toSession() }
            page = response.pageToken
        } while (!page.isNullOrEmpty())
        return sessions
    }

    /** Get the token BEFORE the snapshot so updates arriving during the read are not lost.
     * The caller persists nextToken only after all calendar writes succeed. */
    suspend fun snapshot(source: String, savedToken: String?, now: Instant = Instant.now()): SleepSnapshot {
        suspend fun newToken() = client.getChangesToken(ChangesTokenRequest(
            recordTypes = setOf(SleepSessionRecord::class),
            dataOriginFilters = if (source.isBlank()) emptySet() else setOf(DataOrigin(source)),
        ))
        var token = savedToken ?: newToken()
        val upserts = mutableMapOf<String, SleepSession>()
        val deleted = mutableSetOf<String>()
        if (savedToken != null) {
            var more: Boolean
            do {
                val response = client.getChanges(token)
                if (response.changesTokenExpired) {
                    token = newToken()
                    upserts.clear()
                    deleted.clear()
                    break
                }
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> (change.record as? SleepSessionRecord)?.let {
                            if (source.isBlank() || it.metadata.dataOrigin.packageName == source) {
                                deleted.remove(it.metadata.id)
                                upserts[it.metadata.id] = it.toSession()
                            }
                        }
                        is DeletionChange -> {
                            upserts.remove(change.recordId)
                            deleted += change.recordId
                        }
                    }
                }
                token = response.nextChangesToken
                more = response.hasMore
            } while (more)
        }
        // The fresh snapshot wins over older change entries. Deleted records may have been
        // rewritten after getChanges; a current read of the same ID must be kept.
        val sessions = upserts.filterKeys { it !in deleted }.toMutableMap()
        recent(source, now).forEach { sessions[it.recordId] = it }
        return SleepSnapshot(sessions.values.toList(), token)
    }
}

fun SleepSessionRecord.toSession() = SleepSession(
    recordId = metadata.id, source = metadata.dataOrigin.packageName,
    clientId = metadata.clientRecordId, start = startTime, end = endTime,
    title = title, notes = notes, modified = metadata.lastModifiedTime,
    stages = stages.map { stage -> SleepStage(stage.startTime, stage.endTime, when (stage.stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "Out of bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "REM"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "Awake in bed"
        else -> "Unknown"
    }) },
)
