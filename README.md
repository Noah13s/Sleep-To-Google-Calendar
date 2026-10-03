# Sleep to Calendar

A free, open-source Android app that copies sleep sessions from **Xiaomi Smart Band 10 → Mi Fitness → Android Health Connect → a calendar you select**. Select a Google calendar on the phone to have Android's existing Google account sync upload the events to Google Calendar.

No Tasker, paid bridge app, hosted service, Google Cloud project, API key, OAuth setup, or subscription is required. This app runs on your phone and has no Internet permission. Mi Fitness syncs the band; Android's calendar account handles Google's cloud sync.

## Features

- Reads Health Connect `SleepSessionRecord` records, with Mi Fitness selected by default.
- Imports **every session**, including naps and multiple sessions on the same day.
- Writes actual start/end instants, duration, source title/notes, and available stage totals (light, deep, REM, awake, etc.). Missing stages are reported; session duration is not presented as measured time asleep.
- Reuses events by source + provider client record ID, falling back to Health Connect record ID. Changed times, notes, and stages update the same event.
- Conservatively matches replacement records with new IDs when both sides have exactly one strongly overlapping candidate from the same source. Ambiguous sessions remain separate.
- Keeps a sync marker in the event description to recover identity after retries, crashes, switching back to a calendar, or reinstalling. **Keep the final `[SleepToCalendar:v1:…]` line intact.** Ordinary events are never matched by title or modified.
- Provides a read-only preview, manual **Sync now**, source/calendar selection, permission status, last sync time, and result counts.
- Runs an approximately hourly, unique WorkManager job when automatic sync is enabled **and** background Health Connect read access is supported and granted. Work persists across reboot.

## Requirements

- Android 9 or newer with usable Health Connect. It is built into Android 14+; supported older phones require the free Health Connect app.
- Mi Fitness paired with your Smart Band 10 and allowed to **write Sleep** to Health Connect.
- A writable Android calendar. For Google Calendar, add your Google account to the phone and enable Calendar account sync.

**Essential check:** Health Connect must actually contain **Sleep entries attributed to Mi Fitness**. This app cannot read Mi Fitness's private database or pull directly from the band. Xiaomi's export can vary by app version, region and firmware; permissions alone do not prove that sleep has been exported. Stages appear only when Mi Fitness writes them to Health Connect.

## Phone setup

1. Install the APK built below. Launch **Sleep to Calendar**.
2. In **Mi Fitness**, pair/sync your band, then look under **Profile → Third-party data / Connected apps → Health Connect** (labels vary). Connect it and allow **Sleep writing**. Enable detailed sleep monitoring on the band for stages.
3. Open Health Connect from Android Settings or this app's **Open Health Connect** button. Under **App permissions → Mi Fitness**, confirm Sleep writing is allowed. Under **Data and access → Sleep**, verify real entries from Mi Fitness. New exports may need another band sync; historical backfill is controlled by Mi Fitness.
4. Tap **Allow Sleep access** in Sleep to Calendar and grant reading of Sleep. The app never asks to write health data.
5. Tap **Allow calendar access**, then **Choose a calendar**. Select your desired Google account/calendar. The app does not silently select your primary calendar or create a Google calendar. For a dedicated private sleep calendar, create one in Google Calendar and wait for it to appear on the phone.
6. Leave **Source: Mi Fitness** selected. **Preview sleep data** checks the source without writing events. Other detected sources can be selected; **All apps** may include duplicate imports from other apps.
7. Tap **Sync now**. A second run should reuse the events and report unchanged sessions. Check Google Calendar on the phone and, after account sync, on the web.
8. Enable **Automatically sync every hour** and allow **background health access** if offered. The status explains missing support or permissions. Access can also be granted later through the app's button or Health Connect's additional access settings.
9. Allow this app and Mi Fitness to run in the background in Android battery settings if your phone restricts them. Google Calendar account sync must be enabled for events to reach the web.

### Timing, history and event lifecycle

WorkManager is deferred work, not an exact hourly alarm. Doze, battery restrictions, force-stop, denied permissions and Mi Fitness export delays can postpone updates. There is no supported background-health workaround on versions that lack the feature: use manual sync with the app open. On supported versions, update Android/Health Connect and grant the separate background permission. Force-stopping prevents automatic work until the app is reopened.

Every sync scans the latest **29 days**, with pagination, to catch missed exports and edits within Health Connect's default read allowance. A changes token also follows later edits to older records while valid. Tokens are saved only after successful calendar writes. Expired tokens recover with a fresh recent scan. Unlimited historical access is not requested: records outside the scan window missed during a long interruption/reinstall are not automatically backfilled.

Revoking permissions pauses sync without deleting events. **Source deletions retain calendar events.** Manually deleting an event allows the next sync to recreate it if the source session is still in the recent window or changes feed. Changing calendars copies sessions to the new destination and leaves previous events in place; only the selected destination is maintained. Uninstalling stops sync and retains events.

Events have no reminders and availability **free**. UTC timestamps preserve elapsed time across midnight and daylight-saving transitions; calendar apps display them in their own timezone. Generated titles/descriptions are maintained by sync, so manual edits to those fields may be replaced.

## Build, test and install

The standalone Android project is in [`android/`](android/). Use Android Studio or JDK 17+ and Android SDK 36. Tools and dependencies are free; the Gradle wrapper is included.

Create `android/local.properties` with your SDK path (ignored by Git), for example:

```properties
sdk.dir=D\:/Softwares/Android/Sdk
```

Windows PowerShell:

```powershell
cd android
# Set JAVA_HOME to your JDK or Android Studio's bundled jbr directory.
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb devices
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.noah.sleeptocalendar/.MainActivity
```

On macOS/Linux run `chmod +x gradlew` once and use `./gradlew` (or use `bash ./gradlew`). APK: `android/app/build/outputs/apk/debug/app-debug.apk`. Install future builds with `-r` and the same signing key to retain preferences. Debug builds use the local Android debug key automatically; distribution builds should use your own persistent release signing key. Never commit keys/passwords.

Connected integration test:

```powershell
cd android
.\gradlew.bat connectedDebugAndroidTest
```

The integration test creates and removes one uniquely named **local test calendar**. It does not select/edit your Google calendar or insert fake Health Connect sleep. Grant calendar runtime permissions first (`adb install -g` above). Unit tests cover identity, corrections, replacement ambiguity, naps, sources, stage descriptions and DST. See [`docs/validation.md`](docs/validation.md) for device validation.

## Structure

| Path | Purpose |
| --- | --- |
| `android/app/…/MainActivity.kt` | Setup, permission requests, selection, preview/manual sync |
| `android/app/…/HealthGateway.kt` | Paginated sleep reads, changes feed, record conversion |
| `android/app/…/SyncModel.kt` | Android-independent reconciliation and sleep details |
| `android/app/…/CalendarGateway.kt` | Writable calendars, event identity, safe inserts/updates |
| `android/app/…/SyncRepository.kt` | Permissions, persistent settings, serialized sync |
| `android/app/…/SyncWorker.kt` | Unique background work |
| `apps-script/` | Original Google Fit implementation, preserved as legacy |

## Troubleshooting

- **No sleep:** sync the band in Mi Fitness, check Sleep entries and their writer in Health Connect, and select that source. If Health Connect is empty, Mi Fitness's export is missing; a calendar bridge cannot reconstruct unavailable sleep or stages.
- **No Google calendar listed:** check the account, Calendar sync and edit access. Read-only calendars are excluded.
- **Events only on the phone:** check Google account Calendar sync/connectivity and that you selected a Google calendar rather than a local one.
- **Automatic sync unavailable:** platform support and a separate background health permission are required. Manual sync remains available.
- **Duplicate imports:** prefer Mi Fitness over All apps. Legacy Google Fit events have no Android sync markers and are not adopted or removed. Stop the old Apps Script trigger.
- **Provider/permission failure:** reopen the app, restore access or reselect a removed calendar, then sync manually. Existing events are recovered safely; status is shown in the app.

## Privacy and legacy

Sleep details leave the phone through the calendar account you select. Anyone with access to that calendar can read them. This app has no analytics, ads, backend or Internet permission. Settings/change tokens stay on the phone and preference backup is disabled. An in-app privacy page is also available from Health Connect's permission rationale screen.

The original [`apps-script/SleepDataToCalendar.gs`](apps-script/SleepDataToCalendar.gs) and manifest are preserved unchanged for reference. They depend on legacy Google Fit and are **not the recommended setup**. The Android app has no dependency on them.

Official references: [Health Connect setup](https://developer.android.com/health-and-fitness/health-connect/get-started), [reads, pagination and background permissions](https://developer.android.com/health-and-fitness/health-connect/read-data), [stable releases](https://developer.android.com/jetpack/androidx/releases/health-connect), [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider).

Licensed under the repository's [GPLv3 license](LICENSE).
