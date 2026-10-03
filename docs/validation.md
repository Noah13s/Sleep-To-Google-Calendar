# Validation — 3 October 2026

## Build and automated tests

- Standalone Kotlin Android app, Android SDK 36, Gradle 8.14.3, Android Gradle Plugin 8.13.0, JDK 21 (Java/Kotlin bytecode 17).
- Health Connect uses stable `connect-client:1.1.0`.
- `testDebugUnitTest`: **21 passed** (14 reconciliation/detail tests and 7 Health Connect read/change-feed tests).
- Covered: repeated imports, changed times, stable client IDs, replacement IDs, ambiguous replacement matches, separate naps, overlapping current sessions, different sources, revision precedence, absent/partial stages, DST duration, record pagination including empty final tokens, token acquisition before reads, change pagination, edits outside the recent window, token expiration, deletions and permission failure propagation.
- `assembleDebug` and `assembleDebugAndroidTest`: successful.
- `lintDebug`: successful, **0 errors**. Remaining warnings are dependency update suggestions, English text/localization and preference/KTX style suggestions.
- Merged app manifest checked: no `INTERNET` permission, no Health Connect write permissions. WorkManager contributes normal scheduling/boot/wake permissions.

## Connected phone

OnePlus 8T (KB2003), Android 14. Mi Fitness `3.59.1i`, Health Connect and Google Calendar were present. Mi Fitness had Sleep write permission and actual Sleep records.

- Installed the built APK on the connected phone.
- The user selected the existing **Google Fit Data** Google calendar.
- Real-data verification found **6 Mi Fitness sleep sessions**, **6 matching managed calendar events**, and **sleep stages in all 6 sessions**.
- Every session matched exactly one event with the same start/end and generated sleep details.
- All 6 events had a Google account sync ID and were clean in the Android Calendar Provider, confirming account upload completed.
- Google Calendar's event details were observed on the phone, including duration, Deep/Light/REM totals and the persistent sync marker.
- Repeat manual sync reported **6 sessions, 0 added, 0 updated, 6 unchanged**.
- Background health read access was granted; automatic sync was enabled. Android JobScheduler showed the unique hourly WorkManager job.
- A delayed one-time **SyncWorker ran with the app off-screen**. The last successful sync advanced from 12:13 to 12:21 Europe/Paris, again reporting **6 unchanged sessions**. A subsequent read-only check confirmed the same six uploaded events, with no duplicates.
- The real Calendar Provider integration test passed using an isolated, uniquely named local calendar. It verified two independent sessions, repeat sync, changed duration/stages, provider ID replacement, stable event IDs, marker recovery through a new gateway instance, malformed marker rejection and retained events after a source deletion. The test calendar was removed in `finally`.

The default connected test suite does not touch your selected calendar or write artificial health data. The opt-in real-data test only reads the already-configured pipeline and verifies upload. The separate opt-in background harness schedules one normal delayed SyncWorker against the selected calendar, using the app's normal reconciliation.

## Reproduce optional device verification

After installing both debug APKs and configuring the app:

```powershell
adb shell am instrument -w -e verifyRealData true -e class com.noah.sleeptocalendar.DeviceValidationTest com.noah.sleeptocalendar.test/androidx.test.runner.AndroidJUnitRunner
```

To test a real background run without waiting an hour, enable automatic sync and its permission first:

```powershell
adb shell am instrument -w -e verifyBackground true -e class com.noah.sleeptocalendar.BackgroundValidationTest com.noah.sleeptocalendar.test/androidx.test.runner.AndroidJUnitRunner
adb shell input keyevent HOME
```

Keep the app off-screen for at least 10 seconds, then check that the last successful sync advances. Android may defer the job. Repeat the read-only verification afterward to confirm one event per current session.

Forcing the periodic JobScheduler job early is insufficient: WorkManager still enforces the periodic execution window. The delayed one-time harness exercises the actual SyncWorker without changing the saved hourly schedule.

## Limits of verification

- The six Google uploads were verified through Android's synced provider metadata and the phone's Google Calendar UI; a separate web Calendar login was not used.
- No changes were made to real Health Connect sleep data. Corrections and naps were tested using unit fixtures and the isolated calendar integration test.
- Automatic execution is permission- and Android-scheduler-dependent; an exact hourly delivery time is not guaranteed.
- Xiaomi controls export/backfill and stage completeness. The app cannot import sessions absent from Health Connect.
