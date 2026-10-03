package com.noah.sleeptocalendar

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            textSize = 18f
            setPadding(padding, padding, padding, padding)
            text = """
                Sleep to Calendar — privacy

                This app reads Sleep sessions and sleep stages from Android Health Connect and writes their times, duration, source and available notes/stages to the calendar you explicitly select.

                Calendar read access is used to list writable calendars and find this app's existing events. Calendar write access adds or updates those events. No other calendar events are edited.

                Optional background health access lets Android WorkManager perform the same sync approximately hourly. You can turn automatic sync off at any time.

                Preferences and a Health Connect change token stay on this phone. Each calendar event includes a sync identifier so it can be updated safely after retries or reinstalling. The app has no server, analytics, advertising, or network permission. Device backup of its preferences is disabled.

                A Google calendar's Android account sync sends the calendar events to Google. People with access to your selected calendar can see these sleep details. Choose a private calendar if needed.

                Revoke Sleep/background permissions in Health Connect or calendar permissions in Android app settings. Uninstalling stops syncing but retains calendar events. Source deletions do not delete calendar events. You can remove events yourself in your calendar.
            """.trimIndent()
        }
        val scroll = ScrollView(this).apply { addView(text) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
    }
}
