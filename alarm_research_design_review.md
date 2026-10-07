# Alarm subsystem design review

Date: 2026-10-06. Scope: analysis only, no app code changed.
All paths are relative to `app/src/main/kotlin/com/example/calendaralarm/` unless stated. Line numbers refer to commit `3e3f21d`.

Symptom under investigation: the alarm vibrates, but the dismiss screen (`AlarmActivity`) is missing.

---

## 1. How the alarm path works today

```
AlarmManager ──► AlarmReceiver ──(markRaised)──► startForegroundService(AlarmService)
                     │                                  │ startForeground(notif 42, fullScreenIntent → AlarmActivity)
                     │ silent? → notify(42, silent)     │ vibrate (repeat forever)
                     │                                  │ register SCREEN_ON/USER_PRESENT → startActivity(AlarmActivity)
                     ▼                                  │ Prefs.activeAlarmJson = json
                                                        ▼
  AlarmActivity (buttons) ─► Prefs.activeAlarmJson=null ─► broadcast ActionReceiver ─► stopService + cancel(42) [+ snooze/join]
  Notification actions ─────────────────────────────────► ActionReceiver (same)
  MainActivity.onResume ─► if Prefs.activeAlarmJson != null → startActivity(AlarmActivity)
```

Nobody owns the question "which alarm is ringing right now, and what should the user see?" Each component works out its own partial answer.

### 1.1 Where alarm state lives

| State | Where | Written by | Read by | Meaning in practice |
|---|---|---|---|---|
| `raised_alarm_keys` (SharedPrefs) | Prefs.kt:79-97 | AlarmReceiver.kt:38-53 (on delivery), PollReceiver.kt:55 (prune) | AlarmScheduler.kt:183 → AlarmPlanner.kt:40-44 | "The AlarmManager broadcast was delivered." This is not "the user saw or acknowledged it". |
| `active_alarm_json` (SharedPrefs) | Prefs.kt:65-71 | AlarmService.kt:21 (set), AlarmService.kt:71, AlarmActivity.kt:75/87/101 (clear) | MainActivity.kt:62 | Means "service is probably running". It is never cleared on process death. |
| `active_alarm_codes` (SharedPrefs) | Prefs.kt:73-76 | PollReceiver.kt:78, MainActivity.kt:208 | AlarmScheduler.kt:186 | PendingIntent request codes to cancel. Two writers, last one wins. |
| `last_alarm_schedule_ts` | Prefs.kt:61-63 | Prefs.kt:207, tests | nobody | Dead state left over from Bug B. |
| `AlarmService.eventsJson`, `vibrator`, `screenOnReceiver` | AlarmService.kt:14-16 | onStartCommand | screen-on receiver | In-memory only. Lost on process death. |
| `AlarmActivity` intent extra `events_json` | AlarmActivity.kt:36 | the launcher (notification / screen-on / MainActivity) | the activity once, in onCreate | A frozen snapshot that never updates. |
| Notification id `42` | NotificationHelper.kt:17 | AlarmService (FGS), AlarmReceiver silent path (NotificationHelper.kt:55) | ActionReceiver cancel | A single slot shared by ringing and silent alarms. |
| Pending snooze alarm | AlarmScheduler.kt:117-134 (code `uid.hashCode() xor 0xABCD`) | ActionReceiver.kt:22-31 | AlarmManager only | Not persisted, not in `active_alarm_codes`, lost on reboot. |

These pieces together form an implicit state machine: *scheduled → delivered (raised) → ringing (service + json) → shown (activity) → dismissed/snoozed*. No component models the transitions. Each one infers the state from a different subset of the stores above, and none of the stores can tell "ringing but nobody can see it" apart from "dismissed".

### 1.2 Logic that is spread out or duplicated

- **Alarm key format** is built in three places: AlarmReceiver.kt:45 (from JSON), AlarmPlanner.kt:40 (from event), and AlarmScheduler.kt:74-78 (`alarmKey`, unused). The comment at Prefs.kt:78 gives the old format.
- **Schedule sync** has two implementations with different event windows:
  - PollReceiver.kt:33-82 uses `now .. now+25h`.
  - MainActivity.kt:124-167 uses `visDayStart .. visDayStart+25h` (line 127). This is the "Issue A" from the memory note, and it is **still present**.
  - Effective-flag computation is copy-pasted: PollReceiver.kt:59-70 and MainActivity.kt:211-228.
  - Each `scheduleAll` call first cancels everything the *other* caller registered (AlarmScheduler.kt:186). Opening the app (and the 60 s refresh, MainActivity.kt:41-46) therefore cancels poll-scheduled alarms for events after tomorrow 01:00 until the next poll restores them. A poll whose fetch fails returns early without rescheduling (PollReceiver.kt:27-31).
- **Alarm presentation** (title/subtitle, join label, snooze label) is duplicated in AlarmActivity.kt:41-107 and NotificationHelper.kt:58-145. The "Join Zoom/Teams" label is also in MainActivity.kt:277. The snooze label time is computed at build time and differs from the time actually used, which is recomputed at click time (ActionReceiver.kt:28).
- **Clearing `activeAlarmJson`** happens in four places: AlarmActivity.kt:75/87/101 and AlarmService.kt:71.
- **Vibration control** is shared between AlarmService (owner, AlarmService.kt:58-68) and AlarmActivity.kt:110-121. The volume keys cancel the vibrator behind the service's back, so the service still believes it is ringing.
- **Dead code:** `QuietHoursReceiver` (only referenced for legacy cancel, AlarmScheduler.kt:170-176), `lastAlarmScheduleTs`, and `AlarmScheduler.alarmKey`.

### 1.3 Components with mixed responsibilities

- **AlarmReceiver** does dedup bookkeeping, routes silent vs. ringing, and posts notifications for the silent path.
- **AlarmService** does ringing, publishes state to prefs, and re-launches the UI (screen-on receiver).
- **AlarmActivity** is the view, but it also mutates state (prefs), controls hardware (vibrator), and issues commands.
- **ActionReceiver** is the command handler for all actions and also the activity launcher for Join.
- **MainActivity** handles display, also acts as an alarm scheduler (`scheduleAll` every minute), and also acts as alarm-UI recovery (onResume redirect).
- **AlarmScheduler** is a mix of AlarmManager wrapper, JSON serialisation, snooze policy, and orchestration (`scheduleAll`).

### 1.4 Hidden coupling and races

- **SharedPreferences used as an IPC bus.** `activeAlarmJson` connects AlarmService and MainActivity with no lifecycle guarantee. `raised_alarm_keys` connects AlarmReceiver and the planner.
- **Read-modify-write races on `raised_alarm_keys`:**
  - `markAlarmRaised` (Prefs.kt:83-87) runs on the main thread.
  - `pruneRaisedAlarmKeys` (Prefs.kt:89-97) runs on `Dispatchers.IO` inside PollReceiver.
  - Both read the set, modify it and write it back. A concurrent pair can drop a key, which causes a duplicate alarm.
- **`scheduleAll` vs. an alarm firing.** AlarmPlanner.kt:85-93 notes this race: `scheduleAll` reads `raisedKeys` (AlarmScheduler.kt:183), then cancels and re-plans. If an alarm fires between those two steps, the planner re-schedules it at `now+2s`, and it rings twice (see S5 for what the second ring does).
- **Concurrent `scheduleAll`.** MainActivity (lifecycleScope IO) and PollReceiver (IO coroutine) can run `scheduleAll` at the same time. Both cancel the same codes and both write `activeAlarmCodes`. The loser's codes go untracked and become orphan alarms if the event later changes.
- **`ActionReceiver.ACTION_DISMISS` has no alarm identity** (NotificationHelper.kt:79-82, AlarmActivity.kt:88-90). Any dismiss from a stale notification, stale activity, or old PendingIntent stops *whatever* is currently ringing (ActionReceiver.kt:17-19).
- **One notification id (42)** is used for every ringing alarm and every silent alarm.

### 1.5 Logging gaps (why behaviour is hard to analyse)

- **AppLog drops lines silently until `init` is called** (AppLog.kt:23). `init` is only called from AlarmReceiver, PollReceiver, BootReceiver and AlarmScheduler. A process started by a notification action (ActionReceiver) or by a launcher tap writes nothing to the file until something else calls `init`.
- **Unlogged components:**
  - AlarmService uses `android.util.Log` (AlarmService.kt:19).
  - AlarmActivity, ActionReceiver, NotificationHelper and the MainActivity redirect never log.
  - So the file log shows "AlarmReceiver fired" and then nothing about service start/stop, activity create/destroy, dismiss/snooze, notification swipe, or screen-on relaunch.
- **No alarm instance id in log lines**, so concurrent alarms cannot be told apart. `scheduleAll` does not log which caller invoked it.
- **Lines use `LocalDateTime` without an offset**, and write errors are swallowed (AppLog.kt:30).

---

## 2. Scenarios where the dismiss screen is lost (traced)

Severity tags:

- **[LOST]**: vibration continues and no in-app control surface is reachable except opening the launcher.
- **[CROSS]**: the wrong alarm is dismissed or shown.
- **[SILENT-LOSS]**: the alarm is never presented and never retried.

Android behaviour notes assume targetSdk 34 (app/build.gradle.kts:13). Items marked *(verify)* depend on platform rules that should be confirmed with logcat on the device.

### S1. User presses Back on AlarmActivity: [LOST] when AlarmActivity is the task root

1. AlarmActivity has no back handling, so Back calls `finish()`. The service keeps vibrating.
2. If AlarmActivity was started with `NEW_TASK` while no app task existed (screen locked, app not open; NotificationHelper.kt:74), it is the task root, so Back leaves the user on the home screen.
3. Recovery paths:
   - **Tap the ongoing notification** (NotificationHelper.kt:112). This works only if the notification is still present (see S2).
   - **Screen-on receiver** (AlarmService.kt:37-49). This calls `startActivity` from a background service context. Android 10+ blocks background activity starts. A foreground service is not an exemption, and once the activity has finished the app has no visible window and no task, so the relaunch is likely blocked *(verify: logcat "Background activity start … blocked")*. The original fix (4b1805f) targeted the case where the activity was *paused* and still in the task, which is a different situation.
   - **Open the app from the launcher.** MainActivity.kt:62-68 redirects to AlarmActivity. The user has to know to do this.
4. If MainActivity *is* underneath, Back lands on MainActivity, and `onResume` immediately relaunches AlarmActivity. Back appears to do nothing. That is the opposite inconsistency.

### S2. User swipes the alarm notification away: [LOST]

1. Android 14 lets users dismiss foreground-service and `setOngoing(true)` notifications (except on the lock screen or through Clear-all). The notification at NotificationHelper.kt:123-128 has no `deleteIntent`.
2. The service keeps running and vibrating (AlarmService.kt:67). Nothing re-posts the notification, and nothing logs the swipe.
3. If AlarmActivity is not on screen (S1, S3 or S4), the only recovery is launching the app from the launcher (MainActivity redirect) or the screen-on path, which is probably blocked (S1.3).

### S3. Phone in use when the alarm fires: no screen by design, then S2

1. When the device is unlocked and the screen is on, Android shows the full-screen intent (NotificationHelper.kt:128) as a heads-up notification instead of launching the activity.
2. The heads-up offers Dismiss and Snooze, but AlarmActivity never appears. If the heads-up or shade entry is swiped, see S2.
3. On Android 14+, if `USE_FULL_SCREEN_INTENT` was not granted, the full-screen intent *never* launches the activity, even when locked. MainActivity.kt:300-316 asks once per `onCreate`, and "Not now" leaves it ungranted. The app does not log whether the full-screen intent was granted when the alarm fires.

**Design implication:** the activity can never be guaranteed. The notification is the only control surface the app can keep alive, so it must be impossible to lose silently.

### S4. A silent alarm fires while a ringing alarm is active: [CROSS] / [LOST]

1. AlarmReceiver.kt:24-26 calls `NotificationHelper.showSilentAlarmNotification`, which calls `notify(ALARM_NOTIF_ID=42, silent=true)` (NotificationHelper.kt:55).
2. This replaces the foreground service's ongoing notification with an auto-cancel, non-ongoing notification for *different events*.
3. The original alarm's notification is gone, and the service keeps vibrating for the original events.
4. Tapping the new notification opens AlarmActivity for the *silent* events, and `setAutoCancel` removes the notification.
5. Tapping Dismiss on either surface calls ActionReceiver, which stops the service. The user can believe they dismissed one alarm while the other was actually ringing.

### S5. Second ringing alarm while the first is still ringing: [CROSS]

1. The second AlarmReceiver delivery calls `startForegroundService` again, and `onStartCommand` runs again (AlarmService.kt:18-34):
   - `eventsJson` and `activeAlarmJson` are overwritten. The first alarm's events are no longer known anywhere, but they are already in `raised_alarm_keys` and so are never retried.
   - `registerScreenOnReceiver()` registers a **second** receiver and overwrites the reference (AlarmService.kt:37). The first receiver is never unregistered until the service is destroyed (leak), so screen-on launches the activity twice.
2. AlarmActivity does not override `onNewIntent`. An existing instance brought forward with `REORDER_TO_FRONT` (AlarmService.kt:45, MainActivity.kt:66) or `SINGLE_TOP` (NotificationHelper.kt:74) keeps showing the **first** alarm's text.
3. Dismiss/Snooze from that stale view stops the service, which kills alarm #2. Snooze snoozes alarm #1's events only, so alarm #2 is lost for good.
4. This also happens with the double-fire race in §1.4.

### S6. Dismiss/Snooze from the notification while AlarmActivity exists: [CROSS] (zombie screen)

1. ActionReceiver.kt:17-19 stops the service and cancels the notification. Nothing finishes AlarmActivity.
2. The activity stays in recents or under the lock screen with live buttons.
3. Later, while a *new* alarm rings, the user may tap that zombie's Dismiss. Its identity-free `ACTION_DISMISS` stops the new alarm. That alarm is already marked raised, so it is never retried.
4. The zombie also shows a stale snooze label.

### S7. Process killed while ringing (OOM, user force-stop, OEM battery killer): [SILENT-LOSS] plus a phantom screen

1. `START_NOT_STICKY` (AlarmService.kt:33) means the service is not restarted, and `onDestroy` does not run on process death.
2. The notification disappears with the service. Vibration ends or is orphaned, depending on the platform.
3. `raised_alarm_keys` already contains the key (AlarmReceiver.kt:21), so the planner skips it forever (AlarmPlanner.kt:41-44). The alarm is never re-raised, even if the event has not started yet.
4. `active_alarm_json` stays set (cleared only at AlarmService.kt:71). Next app launch, MainActivity.kt:62-68 redirects to an AlarmActivity for an alarm that is not ringing.
5. Force-stop also clears every AlarmManager alarm, including the poll chain. Nothing restarts it until the app is opened or the device reboots.

### S8. Reboot while ringing, or with a snooze pending: [SILENT-LOSS]

1. AlarmManager is cleared on reboot. BootReceiver.kt:12 only schedules a poll.
2. The ringing alarm and any pending snooze (AlarmScheduler.kt:117-134) are gone. Both event keys are in `raised_alarm_keys`, so the poll will not schedule them again.
3. A stale `active_alarm_json` produces the phantom screen from S7.

### S9. User swipes the app's task from recents: [LOST]

1. Removing the task destroys AlarmActivity (and MainActivity). The foreground service survives (no `stopWithTask`), and vibration continues.
2. Same recovery situation as S1/S2: notification tap if it still exists, otherwise launch the app.

### S10. Volume key pressed on AlarmActivity, then the activity is left

1. AlarmActivity.kt:110-121 cancels the vibrator directly. The service is still "ringing": foreground notification up, `activeAlarmJson` set, screen-on receiver active.
2. Subsequent screen-ons try to relaunch the dismiss screen for a silent "alarm".
3. Not a lost screen, but it adds a fourth state ("ringing but muted") that nothing models.

### S11. Delivery succeeded but presentation failed: [SILENT-LOSS]

AlarmReceiver.kt:21 marks the alarm raised *before* starting the service (AlarmReceiver.kt:31-35). If `startForegroundService` throws, the alarm is recorded as raised although nothing was shown. Possible causes are `ForegroundServiceStartNotAllowedException` or a missing exact-alarm exemption *(verify)*. Similarly, if `events_json` is null, `onStartCommand` returns without calling `startForeground` (AlarmService.kt:20), which leads to the "did not call startForeground" crash.

### S12. AlarmActivity started without extras

AlarmActivity.kt:36 returns early after `setContentView`. The layout's Dismiss/Snooze buttons then have **no click listeners**, so the user sees a dismiss screen whose buttons do nothing. All current launchers pass the extra, so this is latent, but it is a trap for any future launcher.

### S13. Join from the notification

ActionReceiver.kt:34-38 starts an activity from a broadcast receiver that was triggered by a notification action. Android 12+ (targetSdk ≥ 31) blocks this notification trampoline. Join from the notification therefore stops the alarm but probably does not open the meeting *(verify)*. Not a lost dismiss screen, but the alarm ends while the user's intent fails.

---

## 3. Proposed architecture

Principle: **one alarm-instance state machine, one persistent source of truth, one owner of side effects. The activity and notification are pure renderings of that state, and every command names the instance it targets.**

### 3.1 Model

```kotlin
data class AlarmInstance(
    val id: String,                 // stable: hash of sorted event keys + original trigger
    val events: List<AlarmEvent>,   // typed, replaces ad-hoc JSON; key() defined once
    val kind: Kind,                 // RING, SILENT, QUIET_HOURS_EARLY
    val state: State,
    val triggerMs: Long,            // current due time (original or snooze-until)
    val updatedMs: Long,
)
enum class State { SCHEDULED, RINGING, SNOOZED, DISMISSED, MISSED }
```

Transitions are pure, testable functions in one place (`AlarmStateMachine`):

| From | Event | To | Effects |
|---|---|---|---|
| SCHEDULED/SNOOZED | trigger delivered | RINGING (or DISMISSED for SILENT after posting) | ensure service running, post notification |
| RINGING | user dismiss(id) | DISMISSED | stop ringing if no other RINGING |
| RINGING | user snooze(id) | SNOOZED(triggerMs = snoozeUntil) | schedule AM alarm |
| RINGING | ring timeout / event ended | MISSED | post non-ongoing "missed" notification |
| RINGING | notification deleted | RINGING (re-post) *or* DISMISSED (policy choice; make it explicit) | log |
| any non-terminal | calendar sync: event removed/changed | dropped / re-keyed | cancel AM alarm |
| RINGING/SNOOZED/SCHEDULED | boot / process start / poll reconcile | same state | re-arm AM alarm, restart service if RINGING |

Dedup becomes "a terminal state exists for this key". This replaces "the broadcast was delivered", which removes S7, S8 and S11 at the root.

### 3.2 Components

- **`AlarmStore`** (single source of truth): all instances persisted as one JSON document in a file or a single prefs key. Writes are serialised through one `Mutex` or a single-thread executor and use `commit()`. It replaces `raised_alarm_keys`, `active_alarm_json`, `active_alarm_codes` and the implicit snooze state. It exposes a `StateFlow<List<AlarmInstance>>` for in-process observers.
- **`AlarmController`** (the only thing that mutates the store): `onTrigger(id)`, `dismiss(id)`, `snooze(id)`, `onNotificationDeleted(id)`, `reconcile(now, reason)`, `syncFromCalendar(events, flags, now)`. Every transition is logged with id, from→to, and reason. Side effects go through an injected `AlarmEffects` interface (AlarmManager, notifications, service start), so the controller is unit-testable without Robolectric.
- **`ScheduleSync.run(context, reason)`**: the single place that computes the window `now..now+25h`, fetches, evaluates flags, and calls `controller.syncFromCalendar`. Used by PollReceiver, BootReceiver, Settings and MainActivity. MainActivity only displays its own fetched list and never calls `scheduleAll`. Serialise it with a process-wide mutex.
- **`AlarmService`** (the only owner of ringing hardware and the foreground notification):
  - Its intent carries only `alarm_id`. On every start it reads `store.ringing()` and renders all of them: one notification per instance (unique id derived from instance id), one vibration.
  - It registers the screen-on receiver once.
  - It returns `START_STICKY` and recovers from the store when restarted with a null intent. It stops itself when no instance is RINGING.
  - It has a ring timeout.
- **`AlarmActivity`** (a pure view):
  - Manifest: `launchMode="singleTask"`, dedicated `taskAffinity`, `excludeFromRecents="true"`.
  - Takes `alarm_id` only. Collects `store.flow`, renders the current RINGING instance(s), and `finish()`es itself when none remain. This removes zombies (S6) and stale views (S5).
  - Handles `onNewIntent`.
  - Back moves the task to the background (or is ignored) without changing state. It never mutates prefs or the vibrator; buttons call `controller.dismiss(id)` / `snooze(id)`.
- **Notification**:
  - Each ringing notification gets a `deleteIntent` → `controller.onNotificationDeleted(id)` (S2). Silent and quiet-hours notifications use their own ids (S4).
  - All action PendingIntents carry `alarm_id` and use per-instance request codes.
  - Join opens the URL via `PendingIntent.getActivity` directly, avoiding the trampoline (S13). The dismiss state change then happens through `AlarmActivity`, or through a combined activity that dismisses and forwards.
- **`Application.onCreate`**: initialise AppLog there, so no log line is ever dropped.
- **`MainActivity`**: keep the redirect, but base it on `store.ringing()`, which is accurate after process death because the store and `reconcile` agree.

### 3.3 Why this fixes the class of problem

- **One answer to "what is ringing?"** It is the store; everything else renders from it.
- **Stale UI cannot act on the wrong alarm**, because commands carry an id and the controller ignores commands for non-RINGING ids.
- **Losing a surface does not lose the alarm.** Process death, reboot and swipes leave the instance RINGING or SNOOZED in the store, and `reconcile` re-establishes the service, notification and AlarmManager entries.
- **The log can be read as a sequence of state transitions per instance id.**

---

## 4. Stepwise refactor plan (ordered by value ÷ risk)

Each step is shippable on its own.

1. **Observability first (no behaviour change, very low risk).**
   - Add an `Application` subclass that calls `AppLog.init`.
   - Log these lifecycle points (with `events` keys) and add the caller/`reason` to `scheduleAll`:
     - AlarmService: `onStartCommand` (with startId and events keys), `onDestroy`, `onTaskRemoved`, screen-on relaunch attempts.
     - AlarmActivity: `onCreate`/`onNewIntent`/`onResume`/`onPause`/`onDestroy`/Back/volume.
     - ActionReceiver: actions.
     - MainActivity: redirect.
     - `canUseFullScreenIntent()` at alarm time.
   - Add a `deleteIntent` that only logs.
   - Log timestamps with offset.
   - Benefit: lets you confirm which of S1–S13 is actually happening on the device.
2. **Identity on every command + separate notification ids (low risk, fixes S4/S5/S6 cross-kills).**
   - Introduce an instance id (start with a hash of the event keys).
   - Put it in the activity/notification/action intents. ActionReceiver ignores a dismiss/snooze whose id is not the currently active id.
   - Give silent notifications their own id.
   - Override `onNewIntent` in AlarmActivity. Finish AlarmActivity when its id is no longer active (cheap version: check on `onResume`).
3. **Single `ScheduleSync` entrypoint (low-medium risk).**
   - Remove `scheduleAll` from MainActivity (fixes the still-present Issue A at MainActivity.kt:127 and the cross-cancel coupling). Dedupe the flag computation.
   - Serialise with a mutex.
   - Delete `lastAlarmScheduleTs`, `AlarmScheduler.alarmKey`, the `QuietHoursReceiver` legacy cancel path, and the stale Prefs.kt:78 comment.
4. **`AlarmStore` + state machine (medium risk, core fix for S7/S8/S11).**
   - Move dedup from "delivered" to "terminal state". Persist SNOOZED with `snoozeUntil`. Replace `activeAlarmJson`.
   - Migration: seed DISMISSED entries from existing `raised_alarm_keys` so existing users do not get re-fires.
   - Write the transition table as pure unit tests before wiring.
5. **AlarmService as sole owner (medium risk, S2/S7/S10).**
   - Render from the store and use `START_STICKY` with store recovery.
   - Re-post on `deleteIntent` (or explicit policy).
   - Add a ring timeout → MISSED.
   - Move the volume-key mute into a controller command (`mute(id)`) so the state reflects it.
6. **AlarmActivity as pure view (medium risk, S1/S5/S6/S9/S12).**
   - Manifest changes: `singleTask`, own affinity, `excludeFromRecents`.
   - Observe the store; Back no longer drops the alarm. Fail loudly if started without an id rather than showing dead buttons.
7. **Reconcile on boot / app start / every poll (low risk once 4–5 exist, S7/S8).**
   - `BootReceiver` → `controller.reconcile("boot")`. Consider also `MY_PACKAGE_REPLACED`.
8. **Presentation dedupe (low risk, cosmetic).** One `AlarmPresentation` formatter used by both the notification and the activity (titles, join label, snooze label from a single `snoozeUntil` stored on the instance).

---

## 5. Existing tests that protect a refactor

| Test | Type | What it protects | Refactor notes |
|---|---|---|---|
| `app/src/test/.../AlarmPlannerTest.kt:54,68,82,100` | Pure JVM | `planAlarms` trigger computation, quiet-hours EARLY/ALARM/advance-into-quiet-hours | Safe across all steps. `planAlarms` should stay pure. |
| `AlarmPlannerTest.kt:133` (`missed alarm fires late…`) | Pure JVM | Late-recovery `now+2s` behaviour | Its Bug-B half re-implements the removed `lastAlarmScheduleTs` guard locally (lines 124-160). It documents history rather than testing current code. Simplify when step 3 lands. |
| `AlarmPlannerTest.kt:197` (TZID) | Pure JVM | Parser → planner timezone conversion | Safe. |
| `AlarmSchedulingTest.kt:64,71` | Robolectric + `AlarmSimulator` | AppClock injection; stable-ICS event entering the window is scheduled on time (Bug B regression) | Protects step 3 (ScheduleSync). |
| `AlarmSimulatorScenarioTest.kt:48,103,138,172,206` | Robolectric + `AlarmSimulator` | Re-fire after reschedule; quiet-hours early alarm; old recurring series; New York TZ; `raisedAlarmKeys` blocks re-fire until cleared | Test 206 and the line 85-87 setup encode the current `raisedAlarmKeys`/`lastAlarmScheduleTs` semantics. Rewrite them in step 4 to "dismissed instance blocks re-fire". |

**Gaps.**
- No test exercises AlarmService, AlarmActivity, ActionReceiver, notifications, `activeAlarmJson`, snooze, or any lifecycle loss.
- `AlarmSimulator` (test/.../AlarmSimulator.kt:87-98) counts an alarm as "fired" when AlarmReceiver runs. That is exactly the "delivered ≠ presented" conflation behind S7/S11.
- Recommended additions before or with step 4:
  - Pure state-machine tests for every row in §3.1.
  - `AlarmSimulator` operations `dismiss(id)`, `snooze(id)`, `swipeNotification(id)`, `killProcess()` (clear in-memory state, keep the store), and `reboot()` (clear AlarmManager, run BootReceiver).
  - Assertions that after each scenario there is exactly one RINGING instance with a live notification, or none.
  - Robolectric `ShadowNotificationManager` can assert notification ids/actions, and `ShadowApplication.getNextStartedActivity/Service` can assert the launches.
