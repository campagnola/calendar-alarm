# Alarm reliability research: how stock and open-source Android alarm apps keep the dismiss UI reachable

Date: 2026-10-06. Research only; no app code was changed.

**Problem:** sometimes the alarm vibrates but there is no way to dismiss it. The user may have pressed Back or Home on the alarm screen, swiped away the notification or heads-up, or the process may have died.

---

## 0. TL;DR

- **Google's current Clock app** (`com.google.android.deskclock`) is **closed source**.
- **AOSP DeskClock** is the open-source ancestor of Google's Clock. It is still published at
  `https://android.googlesource.com/platform/packages/apps/DeskClock`. The last commit is `04e481f`, dated 2025-02-27, and it was converted to Kotlin.
  - It declares `targetSdkVersion="30"`, so it predates the Android 12 to 14 rules (foreground-service start limits, notification trampolines, user-dismissible ongoing notifications).
  - Treat it as a reference design, not as proof that it behaves correctly on Android 14.
- **DeskClock does not force the alarm activity back onto the screen.** It stays robust by layering several mechanisms:
  1. A **persistent state machine** stored in a database (`FIRED`, `SNOOZE`, `MISSED`, and so on). Every component reads the alarm state from it; none trusts a snapshot taken earlier.
  2. A **foreground service** that owns the ringing and the notification. The notification has a full-screen intent, Snooze and Dismiss actions, and a content intent that reopens the alarm screen.
  3. An **alarm activity that cannot be closed with Back**. It hides the nav bar, re-checks the state on every resume, and closes itself when the alarm ends.
  4. An **auto-silence timeout**, scheduled with AlarmManager. When it expires the alarm moves to `MISSED` and the app posts a missed-alarm notification, so it never rings forever.
  5. **Re-registering all alarm state** on boot (`LOCKED_BOOT_COMPLETED`), time and timezone changes, and app updates.
- **Our app's main gaps**, mapped to the reported symptom:
  1. Back finishes `AlarmActivity` while vibration keeps going.
  2. On Android 14 the user can swipe away the notification or heads-up (the "ongoing" flag no longer prevents that). We set no `deleteIntent`, so the vibration continues with no UI and no way to stop it.
  3. Re-showing the activity on screen-on calls `startActivity` from a foreground service. Android 10+ background-activity-launch rules probably block that.
  4. No auto-silence timeout.
  5. The alarm state (`activeAlarmJson`) can go stale after process death, and an alarm cut off mid-ring is never raised again.

---

## 1. AOSP DeskClock: how it works

Source tree used: `android.googlesource.com/platform/packages/apps/DeskClock` at commit `04e481f37e0b52b74c5a5c7b78b662d1f94e3478`. All paths below are under `src/com/android/deskclock/`.

The GitHub mirror `aosp-mirror/platform_packages_apps_deskclock` returns 404, so it is gone. Cite googlesource instead.

### 1.1 Firing path

`AlarmManager` → `PendingIntent.getService(AlarmService, CHANGE_STATE_ACTION, state=FIRED)` → `AlarmService.onStartCommand` → `AlarmStateManager.handleIntent` → `setFiredState` → `AlarmService.startAlarm`.

- **Scheduling** (`alarms/AlarmStateManager.kt` ~L107-132, `AlarmManagerStateChangeScheduler`):
  - Every state transition is an AlarmManager alarm, scheduled with `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`.
  - The intent carries the instance URI and the target state.
  - It is a `PendingIntent.getService`, with `FLAG_RECEIVER_FOREGROUND` added.
- **"Next alarm" indicator** (`updateNextAlarmInAlarmManager`, ~L269-296):
  - A separate surrogate `setAlarmClock(AlarmClockInfo)` call posts the "next alarm" icon to the status bar and lock screen.
  - Alarm-clock alarms are also exempt from Doze and from most restrictions.
- **`setFiredState`** (~L495-518):
  - Writes `FIRED_STATE` to the database first.
  - Then schedules a transition to `MISSED_STATE` at `alarmTime + alarmTimeout`, which is the auto-silence.
- **`AlarmService.startAlarm`** (`alarms/AlarmService.kt` L70-84):
  - If another alarm is already ringing, that alarm is moved to `MISSED`.
  - Acquires a CPU wake lock (`AlarmAlertWakeLock`).
  - Posts the notification and calls `startForeground`.
  - Starts the klaxon (sound and vibration).
  - Broadcasts `ALARM_ALERT_ACTION`.
  - Listens for phone-call state: an incoming call sets the alarm to `MISSED`.
- **Firing the same instance twice is a no-op** (L170-171: `"Alarm already started for instance"`).

### 1.2 Foreground notification

Source: `alarms/AlarmNotifications.kt` L494-552, `showAlarmNotification`.

```kotlin
.setOngoing(true).setAutoCancel(false).setWhen(0)
.setCategory(CATEGORY_ALARM).setVisibility(VISIBILITY_PUBLIC).setLocalOnly(true)
.addAction(snooze  -> PendingIntent.getService(... createStateChangeIntent(SNOOZE_STATE)))
.addAction(dismiss -> PendingIntent.getService(... createStateChangeIntent(DISMISSED_STATE)))
.setContentIntent(PendingIntent.getActivity(... AlarmActivity for instance))
.setFullScreenIntent(PendingIntent.getActivity(... AlarmActivity, action="fullscreen_activity",
                     FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_USER_ACTION), true)
.setPriority(PRIORITY_MAX)
service.startForeground(ALARM_FIRING_NOTIFICATION_ID, ...)
```

- **No `deleteIntent`.** DeskClock relies on `ongoing` plus the foreground-service notification being non-dismissible. That held when it was written (target 30). It is **no longer true on Android 14 for an unlocked phone** (see §4.1).
- The **notification actions go straight to the state machine** (a service intent), not to UI code. This avoids notification-trampoline restrictions.
- The content intent and the full-screen intent both open `AlarmActivity`. They use distinct actions so the two `PendingIntent`s do not collide.

### 1.3 AlarmActivity

Source: `alarms/AlarmActivity.kt`. Manifest at `AndroidManifest.xml` L141-149:

```xml
<activity android:name=".alarms.AlarmActivity" android:directBootAware="true"
    android:excludeFromRecents="true" android:resizeableActivity="false"
    android:showOnLockScreen="true" android:showWhenLocked="true"
    android:taskAffinity="" android:windowSoftInputMode="stateAlwaysHidden" />
```

- **`taskAffinity=""` and `excludeFromRecents`**: the alarm screen runs in its own task and is not mixed into the main app's back stack.
- **`onCreate`** (L129-214):
  - Loads the instance by ID from the database. If it is missing or not `FIRED`, calls `finish()` immediately.
  - Calls `setShowWhenLocked(true)` and `setTurnScreenOn(true)`, and sets `FLAG_KEEP_SCREEN_ON | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON`.
  - Calls `hideNavigationBar()` (immersive sticky, L404-408), commented "to minimize accidental tap on Home key".
  - Sends `ACTION_CLOSE_SYSTEM_DIALOGS`, which collapses the shade and closes dialogs.
  - Uses `setVolumeControlStream(STREAM_ALARM)`.
- **`onResume`** (L216-246):
  - Re-queries the database and finishes if the alarm is no longer `FIRED`. This avoids showing a stale screen.
  - Registers a receiver for `ALARM_DONE` / `ALARM_SNOOZE` / `ALARM_DISMISS`. `ALARM_DONE` finishes the activity, so a dismiss from the notification closes the screen.
  - Binds to `AlarmService`. While the activity is bound, the service's own snooze/dismiss receiver does nothing, so an action is never processed twice.
- **`onPause`** (L248-257): unbinds and unregisters. It does **not** finish and does **not** stop the alarm.
- **`onBackPressed() { /* Don't allow back to dismiss. */ }`** (L293-295).
- **Volume, camera and headset keys** (`dispatchKeyEvent`, L259-291) snooze, dismiss or do nothing, depending on a user setting.
- **Snooze and dismiss** (L470-510):
  - Set `mAlarmHandled = true`, which guards against a double tap.
  - Call `AlarmStateManager.setSnoozeState` or `deleteInstanceAndUpdateParent`. Those calls stop the service via `AlarmService.stopAlarm`.
  - The screen finishes after an animation.
- **Home and recents.** Nothing re-launches the activity. It leaves the screen, keeps ringing, and the ongoing notification (Snooze, Dismiss, tap to reopen) is the way back.
- **Screen off and on.** Turning the screen off and on again does not re-trigger the full-screen intent. The activity is still at the top of its own task with `showWhenLocked`, so it appears above the keyguard when the screen wakes, as long as the user had not navigated away.

### 1.4 State machine

Instance states are defined in `provider/ClockContract.kt` L160-220 and persisted in a ContentProvider/SQLite database (`AlarmInstance`):

```
SILENT(0) -> LOW_NOTIFICATION(1) -> [HIDE_NOTIFICATION(2)] -> HIGH_NOTIFICATION(3) -> FIRED(5)
FIRED -> SNOOZE(4) -> FIRED ...
FIRED -> MISSED(6)       (auto-silence timeout, incoming call, or a newer alarm pre-empting)
MISSED -> DISMISSED(7)   (after MISSED_TIME_TO_LIVE = 12 h; shows a "missed alarm" notification meanwhile)
any -> PREDISMISSED(8)   (user dismissed an upcoming alarm early)
DISMISSED -> instance deleted, parent alarm rescheduled
```

- **Every transition** writes to the database, updates or clears the notification, and schedules the next transition with AlarmManager. All transitions go through one entry point: `AlarmStateManager.handleIntent` / `setAlarmState`, L907-932.
- **Snooze is persisted.** The new `alarmTime` and `SNOOZE_STATE` are written to the database (L529-573), so a snooze survives a reboot.
- **Missed alarms leave a visible trace**: `showMissedNotification`, L599.

### 1.5 Process death, reboot, time changes, updates

- **`AlarmInitReceiver`** (`AlarmInitReceiver.kt`):
  - Registered for `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `LOCALE_CHANGED` and `MY_PACKAGE_REPLACED`.
  - It is `directBootAware`, so alarms work before the user's first unlock after a reboot.
  - It uses `goAsync()` with a wake lock, then calls `AlarmStateManager.fixAlarmInstances`.
- **`fixAlarmInstances` / `registerInstance`** (L713-898) work out the correct state for every instance from its stored state plus the current time:
  - **`FIRED` and not yet timed out:** `setFiredState` again. The alarm starts ringing again after a reboot or after the process is killed and restarted.
  - **Past `alarmTime`:** fires if within `ALARM_FIRE_BUFFER` of `alarmTime`, otherwise sets `MISSED`.
  - **Past `missedTimeToLive`:** deleted.
  - **`SNOOZE`:** re-posts the snooze notification and re-schedules the `FIRED` transition.
- **`MY_PACKAGE_REPLACED`:** the code comment says "Notifications are canceled by the system on application upgrade", so it rebuilds all notifications.
- **`AlarmService` returns `START_NOT_STICKY`.** It relies on the database plus `AlarmInitReceiver`, not on sticky restarts.

### 1.6 Klaxon

`alarms/AlarmKlaxon.kt` L63-81 vibrates with `AudioAttributes.USAGE_ALARM`. `AsyncRingtonePlayer.kt` plays the ringtone on `USAGE_ALARM` / `STREAM_ALARM` and requests audio focus. Alarm usage is what lets vibration and sound bypass Do Not Disturb and silent mode.

---

## 2. Other open-source alarm apps

### 2.1 Fossify Clock

Repo: `github.com/FossifyOrg/Clock`, active (2026-10).

- **Notification** (`helpers/AlarmNotificationHelper.kt` ~L65-86):
  - Full-screen intent, content intent, Snooze and Dismiss actions.
  - **`setDeleteIntent(dismissIntent)`**: swiping the notification away **dismisses the alarm** and stops the sound.
  - Not ongoing.
- **AlarmActivity** manifest: `excludeFromRecents`, `launchMode="singleTask"`, a separate `taskAffinity`, `showOnLockScreen`.
- **Back is not blocked.** There is no `onBackPressed` override, so Back closes the screen while the alarm keeps ringing. This is the same weakness we have, softened by the auto-dismiss below.
- **Auto-dismiss** after `alarmMaxReminderSecs` (`services/AlarmService.kt`, `startAutoDismiss`). It is a `Handler.postDelayed` inside the process, so it is lost if the process dies.
- **Boot receiver** handles `MY_PACKAGE_REPLACED` and `TIME_SET`.
- **Takeaway:** "swipe = dismiss" is the simplest fix for the swiped-notification case and guarantees there is never a buzzing alarm with no UI. The cost is that an accidental swipe silently kills the alarm.

### 2.2 yuriykulikov "AlarmClock" (Simple Alarm Clock)

Repo: `github.com/yuriykulikov/AlarmClock`. Last commit 2025-03.

- **Hierarchical state machine** in `domain/statemachine` and `AlarmCore.kt`, with persisted state, similar in spirit to DeskClock.
- **Alert activity** `ui/alert/AlarmAlertFullScreen.kt`:
  - `onBackPressed() { // Don't allow back to dismiss }` (L228).
  - Manifest uses `launchMode="singleInstance"`, `taskAffinity=""`, `excludeFromRecents`.
- **Notification** (`notifications/NotificationsPlugin.kt` L55-90): ongoing, full-screen intent, content intent, Snooze and Dismiss actions, **no `deleteIntent`**. It shares DeskClock's Android 14 weakness.
- **Receiver** handles `TIME_SET`, `MY_PACKAGE_REPLACED` and boot.

**Common pattern across all three:** a separate-task alert activity, Back disabled (two of three), a foreground-service notification with actions and a reopen intent, persisted state, and re-registration on boot or update. None of them forcibly re-launches the activity after Home. They rely on the notification for that.

---

## 3. Our implementation (as of `3e3f21d`)

All paths are under `app/src/main/kotlin/com/example/calendaralarm/`.

**Firing path:** `AlarmScheduler.scheduleAlarm` (`setExactAndAllowWhileIdle`, broadcast) → `AlarmReceiver` → `markRaised` → `startForegroundService(AlarmService)`. The service calls `startForeground(notification)`, starts vibration, and registers a receiver for `SCREEN_ON`/`USER_PRESENT` that calls `startActivity(AlarmActivity)`.

- `targetSdk = 34`. Vibration only (no sound).
- Notification (`NotificationHelper.buildAlarmNotification`): ongoing, `PRIORITY_MAX`, `CATEGORY_ALARM`, full-screen intent, content intent = `AlarmActivity`, Dismiss / Snooze / Join actions.
- Alarm "state" is a single `Prefs.activeAlarmJson` string. `AlarmService.onStartCommand` sets it; `onDestroy` and the activity's buttons clear it. `MainActivity.onResume` redirects to `AlarmActivity` while it is non-null.

---

## 4. Gaps, mapped to "rings with no dismiss UI" scenarios

### 4.1 Notification or heads-up swiped away (Android 14+, phone unlocked)

**This is the most likely cause.**

- Android 14 behavior change: `setOngoing(true)` / `FLAG_ONGOING_EVENT` notifications are **user-dismissible** except when the phone is locked or on "Clear all". The only exempt categories are CallStyle, media, DPC and the search selector; **`CATEGORY_ALARM` is not exempt**. Source: https://developer.android.com/about/versions/14/behavior-changes-all
- Foreground-service notifications have been dismissible since Android 13. The service keeps running after the notification is swiped.
- When the phone is unlocked and in use, a full-screen intent does **not** launch the activity. It shows a heads-up notification instead, so the heads-up is often the only UI, and a sideways swipe removes it.
- We set **no `deleteIntent`** (`NotificationHelper.kt`, builder chain), so nothing reacts. Vibration continues, and the only ways back are opening the app (the `MainActivity` redirect) or Settings → Task manager.
- DeskClock and yuriykulikov have the same latent hole. Fossify closes it with `deleteIntent = dismiss`.

### 4.2 Back pressed on AlarmActivity

- `AlarmActivity.kt` has no `onBackPressed` / `OnBackPressedCallback`, so Back finishes the activity while `AlarmService` keeps vibrating.
- If `MainActivity` is under it in the same task, its `onResume` redirect bounces the user back, which partly hides the problem. When the full-screen intent started the activity in a fresh task, Back leads straight to the launcher.
- DeskClock and yuriykulikov swallow Back.

### 4.3 Home or recents pressed, or app switch

- Same outcome as 4.2. The only recovery is the notification, which may be gone (4.1).
- Our `SCREEN_ON` re-launch (`AlarmService.registerScreenOnReceiver`) is an attempt at recovery. However, `startActivity` from a foreground service is **blocked by background-activity-launch rules on Android 10+**: a foreground service is not one of the exemptions (https://developer.android.com/guide/components/activities/background-starts).
  - It may appear to work in testing only through incidental exemptions, for example the alarm activity still holding a visible window.
  - Check logcat for `Background activity start` / `BAL_BLOCK` messages to confirm.
  - The sanctioned way to "pop the screen again" is to **re-post the notification with the full-screen intent** (`nm.notify(ALARM_NOTIF_ID, …)`). The system launches the full-screen intent when the screen is off or the keyguard is showing.

### 4.4 No auto-silence timeout

- `AlarmService` vibrates until it is explicitly stopped. If the UI is lost, the phone buzzes indefinitely.
- DeskClock schedules `FIRED → MISSED` at `alarmTime + timeout` with AlarmManager and posts a "missed" notification. Fossify uses an in-process timeout.

### 4.5 State is not a real state machine, and goes stale after process death

- `activeAlarmJson` is set in `onStartCommand` and cleared only in `onDestroy` or by the activity's buttons. If the process is killed while ringing (out-of-memory, app update, force-stop by the system), `onDestroy` never runs. `activeAlarmJson` stays set, and `MainActivity` keeps redirecting to an `AlarmActivity` for an alarm that is no longer ringing.
- `AlarmReceiver.markRaised` runs **before** the service starts. If the alarm is interrupted mid-ring (process death, reboot), the planner skips the event as "already raised", so it is never re-fired and no missed-alarm notice is shown. DeskClock persists `FIRED`, and `registerInstance` re-fires it after a reboot if it has not timed out.
- `AlarmActivity` never re-validates. There is no `onResume` check that the alarm is still active, and no receiver to finish when the alarm is dismissed from the notification. A stale `AlarmActivity` left in the back stack can later send `ACTION_SNOOZE` and **re-arm an alarm the user already dismissed**.
- `AlarmActivity` clears `activeAlarmJson` itself before broadcasting. State changes are spread across the activity, `ActionReceiver` and the service, instead of going through one transition function.

### 4.6 Snooze is not persisted, and the boot and update hooks are thin

- `AlarmScheduler.scheduleSnooze` exists only in AlarmManager. After a reboot, `BootReceiver` only schedules a poll, so a pending snooze is lost: the event is already in `raisedAlarmKeys`, so it will not be re-planned.
- `BootReceiver` handles only `BOOT_COMPLETED`. It lacks:
  - `LOCKED_BOOT_COMPLETED` with `directBootAware`. Without it, nothing fires until the user unlocks the phone for the first time after a reboot.
  - `MY_PACKAGE_REPLACED`. An app update kills an active alarm, and its notification is cancelled.
  - `TIME_SET` and `TIMEZONE_CHANGED`. RTC alarms are absolute UTC, so this is less critical for us, but a reschedule is cheap.

### 4.7 Smaller issues found along the way

- **Vibration lacks `USAGE_ALARM` attributes** (`AlarmService.startVibration`). On Android 13+, vibration without alarm usage can be suppressed by Do Not Disturb or by ring mode. Use `vibrator.vibrate(effect, VibrationAttributes.createForUsage(USAGE_ALARM))` on API 33+, or `AudioAttributes` with `USAGE_ALARM` below that. DeskClock: `AlarmKlaxon.kt` L76-81.
- **The notification "Join" action is a trampoline.** `ActionReceiver.ACTION_JOIN` calls `startActivity` from a broadcast that a notification action started. Notification trampolines are blocked for targetSdk 31+, so the button probably does nothing from the notification (it works from the activity, which is in the foreground). Use `PendingIntent.getActivity` for that action instead.
- **The volume key cancels vibration but leaves the alarm "active"** (`AlarmActivity.onKeyDown`). The service, notification and `activeAlarmJson` stay alive with no vibration, a half-state. Make it an explicit snooze or dismiss (DeskClock's setting) or a deliberate "silence" state.
- **AlarmActivity manifest** lacks `taskAffinity=""`, `excludeFromRecents` and `launchMode="singleInstance"`/`singleTask`. As a result it mixes into the main task, which makes Back and recents behaviour unpredictable.
- **The full-screen-intent permission** is checked only in `MainActivity.onCreate`. If it is revoked later, alarms silently degrade to heads-up only. Consider also checking at fire time, logging it, and showing a reminder.

---

## 5. Prioritized suggestions

| # | Change | Fixes | Effort |
|---|--------|-------|--------|
| 1 | **Add a `deleteIntent`** to the alarm notification. The handler (in the service or a receiver) either **re-posts the same notification** (preferred: keeps the alarm and its UI alive; re-posting with the full-screen intent also re-pops the screen if locked) or treats the swipe as **snooze**. Avoid "swipe = dismiss" for calendar events because accidental swipes are common, though Fossify chooses it. | 4.1 | S |
| 2 | **Block Back in `AlarmActivity`** (`onBackPressedDispatcher.addCallback { }`; leave Back to the Snooze and Dismiss buttons). Optionally hide the nav bar as DeskClock does. Give the activity its own task: `taskAffinity=""`, `excludeFromRecents="true"`, `launchMode="singleInstance"`. | 4.2, 4.7 | S |
| 3 | **Replace the `SCREEN_ON` `startActivity` with re-posting the full-screen-intent notification.** Re-post on `SCREEN_ON`, from the `deleteIntent` handler, and optionally from a periodic in-service tick (for example every 30-60 s while firing, if the activity is not resumed). The system launches the full-screen intent when locked or with the screen off; otherwise the heads-up reappears. This is the sanctioned path under background-activity-launch rules. | 4.1, 4.3 | S-M |
| 4 | **Add an auto-silence timeout** scheduled with AlarmManager, not a Handler. For example, at `fireTime + N min` (or event start + X) stop the service and post a normal, non-ongoing "Missed alarm: <event>" notification. This guarantees no endless faceless buzzing. | 4.4 | S |
| 5 | **Introduce a small persisted alarm-instance state** keyed by the event key you already use (`uid_start_end`), with states `SCHEDULED → FIRED → SNOOZED(until) → FIRED … → DISMISSED / MISSED`. Route every transition (activity buttons, notification actions, timeout, boot) through one function in one place, mirroring `AlarmStateManager.setAlarmState`. In that model:<br>• `markRaised` becomes "state = FIRED".<br>• `activeAlarmJson` is derived from "any instance in FIRED".<br>• `AlarmActivity.onResume` re-checks the state and finishes if it is not FIRED.<br>• The activity listens for a local "alarm done" signal and finishes.<br>• Snooze persists `SNOOZED(until)`. | 4.5, 4.6 | M |
| 6 | **Boot and update recovery:**<br>• Handle `LOCKED_BOOT_COMPLETED` (`directBootAware` receiver and storage), `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED`.<br>• On each, walk the persisted instances: FIRED and not timed out → fire again; SNOOZED → re-arm, or fire if overdue; FIRED and past the timeout → missed notification.<br>• Clear a stale `activeAlarmJson` when no service is running. | 4.5, 4.6 | M |
| 7 | **Vibrate with `USAGE_ALARM`** attributes. | 4.7 | XS |
| 8 | **Make the notification "Join" action a `PendingIntent.getActivity`** for the meeting URL. To also dismiss the alarm, either open a tiny trampoline-free activity that dismisses and forwards, or dismiss in `onCreate` of a no-UI activity. | 4.7 | S |
| 9 | Consider `setAlarmClock()` (an `AlarmClockInfo` whose show-intent opens `EventDetailActivity`) for the **next** event alarm, as DeskClock does. It gives the status-bar alarm icon, the strongest Doze exemption, and is the API intended for user-facing alarms. | robustness | S |
| 10 | When firing, check `NotificationManager.canUseFullScreenIntent()` (API 34) and `AppLog` the result. If the permission is revoked, post a separate reminder that the alarm screen cannot appear. | diagnosis | XS |

**Suggested order:** items 1, 2, 3 and 7 are small and directly address the reported symptom. Item 4 is the safety net. Items 5 and 6 are the structural fix that makes the rest coherent and also cover process death and reboot.

### Test matrix

Run each scenario on Android 14+ (or whatever the user's device runs), with the phone both unlocked and locked, while the alarm is firing:

- Press Back.
- Press Home, then wait for the screen to time out and wake the phone.
- Open recents and swipe the app away.
- Swipe the heads-up sideways.
- Swipe the notification away in the shade.
- Use "Clear all".
- Run `adb shell am kill com.example.calendaralarm`, or `am crash`, during the alarm.
- Reboot during an alarm, and during a snooze.
- `adb install -r` during an alarm.
- Turn on Do Not Disturb, and switch ring mode to silent.

In each case, expect one of two outcomes: a reachable dismiss UI within a short time, or the vibration stops and a missed-alarm notification appears.

---

## 6. Source references

- **AOSP DeskClock** (commit `04e481f`, 2025-02-27). Browse at https://android.googlesource.com/platform/packages/apps/DeskClock/+/04e481f37e0b52b74c5a5c7b78b662d1f94e3478/
  - `AndroidManifest.xml` (L25 targetSdk 30; L141-182 AlarmActivity, AlarmInitReceiver, AlarmStateManager, AlarmService)
  - `src/com/android/deskclock/alarms/AlarmService.kt`
  - `src/com/android/deskclock/alarms/AlarmActivity.kt`
  - `src/com/android/deskclock/alarms/AlarmNotifications.kt` (L494-552)
  - `src/com/android/deskclock/alarms/AlarmStateManager.kt` (L107-132, 269-296, 495-932)
  - `src/com/android/deskclock/alarms/AlarmKlaxon.kt`
  - `src/com/android/deskclock/AlarmInitReceiver.kt`
  - `src/com/android/deskclock/provider/ClockContract.kt`, `provider/AlarmInstance.kt` (timeout, `MISSED_TIME_TO_LIVE_HOUR_OFFSET = 12`)
- **Fossify Clock:** https://github.com/FossifyOrg/Clock
  - `app/src/main/kotlin/org/fossify/clock/helpers/AlarmNotificationHelper.kt`
  - `activities/AlarmActivity.kt`
  - `services/AlarmService.kt`
- **yuriykulikov AlarmClock:** https://github.com/yuriykulikov/AlarmClock
  - `app/src/main/java/com/better/alarm/ui/alert/AlarmAlertFullScreen.kt`
  - `notifications/NotificationsPlugin.kt`
  - `domain/statemachine/`
- **Android 14 dismissible ongoing notifications:** https://developer.android.com/about/versions/14/behavior-changes-all
- **Background activity launch restrictions:** https://developer.android.com/guide/components/activities/background-starts
- **Google Clock** (`com.google.android.deskclock`): proprietary, no public source.
- **Our code:** `app/src/main/kotlin/com/example/calendaralarm/` (`AlarmReceiver.kt`, `AlarmService.kt`, `AlarmActivity.kt`, `ActionReceiver.kt`, `NotificationHelper.kt`, `BootReceiver.kt`, `AlarmScheduler.kt`, `Prefs.kt`, `MainActivity.kt`) and `app/src/main/AndroidManifest.xml`.
