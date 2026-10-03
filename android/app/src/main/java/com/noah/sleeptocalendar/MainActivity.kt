package com.noah.sleeptocalendar

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var repository: SyncRepository
    private lateinit var content: LinearLayout
    private lateinit var healthStatus: TextView
    private lateinit var backgroundStatus: TextView
    private lateinit var resultStatus: TextView
    private lateinit var calendarButton: Button
    private lateinit var sourceButton: Button
    private lateinit var syncButton: Button
    private lateinit var previewButton: Button
    private lateinit var backgroundButton: Button
    private lateinit var automatic: Switch
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    private val sleepLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
    private val backgroundLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
    private val calendarLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = SyncRepository(this)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        scroll.addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        text("Sleep to Calendar", 30f, true)
        text("Your nights and naps, in your calendar.", 17f)
        text("Smart Band → Mi Fitness → Health Connect → Calendar", 14f)
        space()
        text("1. Connect your sleep data", 20f, true)
        text("In Mi Fitness, enable Health Connect and allow it to write Sleep. Sync your band, then allow this app to read Sleep.")
        healthStatus = text("")
        button("Allow Sleep access") {
            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
                sleepLauncher.launch(setOf(HealthPermission.getReadPermission(SleepSessionRecord::class)))
            else openHealth()
        }
        button("Open Health Connect") { openHealth() }
        sourceButton = button("") { selectSource() }
        space()
        text("2. Choose your calendar", 20f, true)
        text("Choose a Google calendar for cloud sync, or any writable Android calendar. Sleep details will be visible to people who can read that calendar.")
        button("Allow calendar access") { calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
        calendarButton = button("") { selectCalendar() }
        space()
        text("3. Sync", 20f, true)
        text("Imports the last 29 days and follows later changes. Each session gets its own event, including naps.")
        previewButton = button("Preview sleep data") { preview() }
        syncButton = button("Sync now") { sync() }
        resultStatus = text(repository.settings.status)
        automatic = Switch(this).apply {
            text = "Automatically sync every hour"
            isChecked = repository.settings.automatic
            setOnCheckedChangeListener { _, enabled ->
                lifecycleScope.launch {
                    SyncRepository.mutex.withLock { repository.settings.automatic = enabled }
                    if (enabled && runCatching { repository.backgroundAvailable() && !repository.backgroundPermission() }.getOrDefault(false))
                        backgroundLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
                    refresh()
                }
            }
        }
        content.addView(automatic)
        backgroundStatus = text("")
        backgroundButton = button("Allow background health access") {
            backgroundLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
        }
        text("Android decides when background work runs. Battery restrictions, force-stop, and Mi Fitness sync delays can postpone events. Internet is only needed by your Google calendar account sync.", 14f)
        button("Open app / battery settings") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        button("Privacy & permissions") { startActivity(Intent(this, PrivacyActivity::class.java)) }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onStop() {
        syncJob?.cancel() // foreground Health Connect reads must not continue invisibly
        refreshJob?.cancel()
        super.onStop()
    }

    private fun refresh() {
        if (!::healthStatus.isInitialized) return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            val available = HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE
            val sleep = available && runCatching { repository.sleepPermission() }.getOrDefault(false)
            val bgAvailable = available && runCatching { repository.backgroundAvailable() }.getOrDefault(false)
            val background = bgAvailable && runCatching { repository.backgroundPermission() }.getOrDefault(false)
            val calendar = repository.calendarPermission()
            healthStatus.text = if (sleep) "Sleep read access granted" else if (available) "Sleep read access needed" else "Health Connect needs installation or an update"
            calendarButton.text = if (calendar) repository.settings.calendarLabel else "Choose a calendar (permission needed)"
            sourceButton.text = "Source: ${sourceLabel(repository.settings.source)}"
            backgroundButton.isEnabled = bgAvailable
            backgroundStatus.text = when {
                !bgAvailable -> "Background reads are unavailable on this Health Connect version. Manual sync works when the app is open."
                !background -> "Background health permission needed for automatic sync. Manual sync is available."
                !repository.settings.automatic -> "Background access granted. Automatic sync is off."
                !sleep || !calendar || repository.settings.calendarId < 0 -> "Finish Sleep and calendar setup to start automatic sync."
                else -> "Automatic sync enabled · approximately hourly, including after reboot."
            }
            SyncSchedule.update(this@MainActivity, repository.settings.automatic && background && sleep && calendar && repository.settings.calendarId >= 0)
            resultStatus.text = repository.settings.status + (repository.settings.lastSuccess?.let { "\nLast successful sync: ${formatTime(Instant.parse(it))}" } ?: "")
        }
    }

    private fun selectCalendar() {
        if (!repository.calendarPermission()) {
            calendarLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)); return
        }
        lifecycleScope.launch {
            try {
                val choices = withContext(Dispatchers.IO) { CalendarGateway(contentResolver).calendars() }
                if (choices.isEmpty()) { showMessage("No writable calendars. Add your Google account in Android settings and enable Calendar sync, then return here."); return@launch }
                AlertDialog.Builder(this@MainActivity).setTitle("Destination calendar")
                    .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                        lifecycleScope.launch {
                            SyncRepository.mutex.withLock { repository.settings.selectCalendar(choices[index]) }
                            refresh()
                        }
                    }.setNegativeButton("Cancel", null).show()
            } catch (e: Exception) { showMessage("Unable to list calendars: ${e.javaClass.simpleName}") }
        }
    }

    private fun selectSource() {
        lifecycleScope.launch {
            try {
                if (!repository.sleepPermission()) throw SetupRequired("Allow Sleep access first.")
                val sources = withContext(Dispatchers.IO) { HealthGateway(repository.client()).recent("").map { it.source }.distinct() }
                val packages = (listOf("com.xiaomi.wearable") + sources + listOf(repository.settings.source)).distinct().filter { it.isNotBlank() }
                val values = packages + ""
                AlertDialog.Builder(this@MainActivity).setTitle("Sleep source")
                    .setItems(values.map { sourceLabel(it) }.toTypedArray()) { _, index ->
                        lifecycleScope.launch {
                            SyncRepository.mutex.withLock { repository.settings.source = values[index] }
                            refresh()
                        }
                    }.setNegativeButton("Cancel", null).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showMessage(e.message ?: "Unable to read sleep sources.") }
        }
    }

    private fun preview() = runForeground {
        if (!repository.sleepPermission()) throw SetupRequired("Allow Sleep read access first.")
        val sessions = withContext(Dispatchers.IO) { HealthGateway(repository.client()).recent(repository.settings.source) }
        val summary = if (sessions.isEmpty()) "No sessions from ${sourceLabel(repository.settings.source)} in the last 29 days.\n\nOpen Mi Fitness and sync the band. In Health Connect, check Data and access → Sleep. If Mi Fitness has not written Sleep there, this app cannot import it."
        else "${sessions.size} sessions found. Most recent ${minOf(10, sessions.size)}:\n\n" + sessions.sortedByDescending { it.start }.take(10).joinToString("\n\n") {
            "${formatTime(it.start)} → ${formatTime(it.end)}\n${sleepDetails(it)}"
        }
        AlertDialog.Builder(this@MainActivity).setTitle("Sleep preview · no calendar writes").setMessage(summary).setPositiveButton("Close", null).show()
    }

    private fun sync() = runForeground {
        repository.sync(background = false)
        refresh()
    }

    private fun runForeground(block: suspend () -> Unit) {
        if (syncJob?.isActive == true) return
        syncJob = lifecycleScope.launch {
            syncButton.isEnabled = false; previewButton.isEnabled = false
            resultStatus.text = "Reading sleep data…"
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                val message = when (e) {
                    is SetupRequired -> e.message.orEmpty()
                    is SecurityException -> "Permission denied. Check Health Connect Sleep access and calendar permissions."
                    else -> "Sync could not complete (${e.javaClass.simpleName}). Try again; existing events will be reused."
                }
                repository.settings.status = message
                resultStatus.text = message
            } finally {
                syncButton.isEnabled = true; previewButton.isEnabled = true
                if (resultStatus.text == "Reading sleep data…") resultStatus.text = repository.settings.status
            }
        }
    }

    private fun openHealth() {
        val intent = if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        else Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
        runCatching { startActivity(intent) }.onFailure { showMessage("Open Health Connect from Android Settings, or install it from Google Play.") }
    }

    private fun sourceLabel(source: String): String = when (source) {
        "" -> "All apps (may include duplicate imports)"
        "com.xiaomi.wearable" -> "Mi Fitness"
        else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(source, 0)).toString() }.getOrDefault(source)
    }
    private fun showMessage(message: String) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show() }
    private fun formatTime(instant: Instant) = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm").withZone(ZoneId.systemDefault()).format(instant)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun space() { content.addView(View(this), LinearLayout.LayoutParams(1, dp(20))) }
    private fun text(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(28, 48, 46)); setPadding(0, dp(6), 0, dp(8))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(48)
        setOnClickListener { action() }; content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
