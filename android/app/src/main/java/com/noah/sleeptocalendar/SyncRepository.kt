package com.noah.sleeptocalendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

class SetupRequired(message: String) : Exception(message)

class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    var source: String
        get() = prefs.getString("source", "com.xiaomi.wearable")!!
        set(value) { prefs.edit().putString("source", value).remove("token").commit() }
    val calendarId: Long get() = prefs.getLong("calendar", -1)
    val calendarIdentity: String? get() = prefs.getString("calendarIdentity", null)
    val calendarLabel: String get() = prefs.getString("calendarLabel", "Choose a calendar")!!
    fun selectCalendar(choice: CalendarChoice) {
        prefs.edit().putLong("calendar", choice.id).putString("calendarIdentity", choice.identity)
            .putString("calendarLabel", choice.label).remove("token").commit()
    }
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", false)
        set(value) { prefs.edit().putBoolean("automatic", value).commit() }
    var token: String?
        get() = prefs.getString("token", null)
        set(value) { prefs.edit().putString("token", value).commit() }
    var status: String
        get() = prefs.getString("status", "No sync yet")!!
        set(value) { prefs.edit().putString("status", value).commit() }
    var lastSuccess: String?
        get() = prefs.getString("success", null)
        set(value) { prefs.edit().putString("success", value).commit() }
}

class SyncRepository(private val context: Context) {
    val settings = SyncSettings(context)
    fun calendarPermission() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun client(): HealthConnectClient {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            throw SetupRequired("Install or update Health Connect, then reopen this app.")
        return HealthConnectClient.getOrCreate(context)
    }
    suspend fun sleepPermission() = HealthPermission.getReadPermission(SleepSessionRecord::class) in client().permissionController.getGrantedPermissions()
    fun backgroundAvailable() = client().features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    suspend fun backgroundPermission() = backgroundAvailable() && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client().permissionController.getGrantedPermissions()

    suspend fun sync(background: Boolean): SyncCounts = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!calendarPermission()) throw SetupRequired("Allow calendar access to sync.")
            val health = client()
            if (!sleepPermission()) throw SetupRequired("Allow Sleep read access in Health Connect.")
            if (background && !backgroundPermission()) throw SetupRequired("Background health access is unavailable or denied. Open the app and use Sync now.")
            val calendars = CalendarGateway(context.contentResolver)
            val selected = calendars.calendars().find { it.id == settings.calendarId && it.identity == settings.calendarIdentity }
                ?: throw SetupRequired("Choose a writable calendar. The previous calendar may have been removed.")
            val snapshot = HealthGateway(health).snapshot(settings.source, settings.token)
            val result = calendars.sync(selected.id, snapshot.sessions)
            settings.token = snapshot.nextToken
            settings.lastSuccess = Instant.now().toString()
            settings.status = if (result.read == 0) "No sleep sessions found. Sync Mi Fitness and verify Sleep entries in Health Connect." else result.toString()
            result
        }
    }

    companion object {
        // Manual and scheduled work share one process-wide lock, including configuration
        // changes, so they cannot insert the same event concurrently.
        val mutex = Mutex()
    }
}
