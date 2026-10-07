# Alarm manual device test checklist

Unit tests (Robolectric) cover the store, state machine and rendering logic. The items below depend on real platform behaviour and need a device. Use Android 14+ if possible, and an API 31-32 device or emulator for section E.

**Setup:** make an event that starts about 6 minutes from now (the alarm rings 5 minutes before), and turn on Settings > Developer options > "Stay awake" only where noted.
**Log:** `adb shell run-as com.example.calendaralarm cat files/calarm.log`, plus `adb logcat -s CalAlarm ActivityTaskManager ActivityManager`.

**Expected outcome for every item:** within a few seconds you can reach a dismiss UI (the alarm screen or the ongoing notification with Dismiss/Snooze), or the vibration has stopped and a "Missed alarm" notification is showing. The log shows one `RINGING -> ...` transition per alarm id.

## A. Losing the UI while it rings (run with the phone locked, then again unlocked)
- [ ] Press Back on the alarm screen: nothing happens, and it keeps ringing.
- [ ] Press Home, let the screen time out, then wake the phone: the notification is re-posted (full-screen when locked, heads-up when unlocked). The log shows `re-posting notifications (SCREEN_ON)`, and no "Background activity start ... blocked".
- [ ] Open recents and swipe the app away: it keeps ringing, and the notification stays.
- [ ] Swipe the heads-up sideways: it is re-posted under the same id (log: `notification deleted`).
- [ ] Swipe the notification away in the shade: it is re-posted.
- [ ] Use "Clear all" in the shade: the notification survives or is re-posted.
- [ ] Do nothing for 10 minutes: the vibration stops, a "Missed alarm: ..." notification appears (not ongoing), and the alarm screen closes.

## B. Commands
- [ ] Dismiss from the notification while the alarm screen is open: the screen closes by itself.
- [ ] Snooze: the time on the button and on the notification action is the time the alarm rings again.
- [ ] Join (Zoom/Teams event) from the notification, phone unlocked: the meeting app opens and the alarm stops. Confirm logcat has no "trampoline" or "blocked" message.
- [ ] Join from the notification, phone locked: you are asked to unlock, then the meeting opens and the alarm stops.
- [ ] Join from the alarm screen: same result.

## C. Several alarms
- [ ] Two events 2 minutes apart: two separate notifications, and one vibration. Dismissing one leaves the other notification and the vibration running.
- [ ] A silent (quiet-hours) alarm fires while another alarm rings: the ringing notification stays, and a separate silent notification appears.

## D. Process death, reboot, upgrade
- [ ] `adb shell am kill com.example.calendaralarm` while ringing (with the app in the background): the service restarts by itself (START_STICKY) and the notification and vibration come back. If they do not, they come back at the next poll (within 15 minutes).
- [ ] `adb shell am crash com.example.calendaralarm` while ringing: same result.
- [ ] Reboot while ringing: after boot it rings again until its 10-minute timeout, or the "Missed alarm" notification appears.
- [ ] Reboot during a snooze: it rings at the snooze time.
- [ ] `adb install -r` of the new build while an old-version (pre-AlarmStore) alarm is ringing or snoozed: no crash. The old notification disappears, and the log shows `Migrating legacy alarm state`. The old snooze is cancelled, not delivered.
- [ ] `adb install -r` while a new-version alarm rings: it rings again after the update (MY_PACKAGE_REPLACED reconcile).
- [ ] Change the time zone, or set the clock manually: alarms are re-armed (log: `reconcile: TIME_SET` or `TIMEZONE_CHANGED`).

## E. Permissions
- [ ] Android 14: revoke "Display over lock screen" (full-screen intent), then let an alarm ring while locked. It shows as a heads-up only, and a "Lock-screen alarm permission needed" notification opens the right Settings page.
- [ ] API 31-32: revoke "Alarms & reminders". The app does not crash, the log shows `no exact-alarm permission`, and a reminder notification opens the right Settings page. The alarm still fires, possibly late.
- [ ] Do Not Disturb on, and ring mode set to silent: it still vibrates (USAGE_ALARM).

## F. Foreground-service start rules (Android 12+)
- [ ] An alarm fires with the app idle in the background for a long time (Doze: `adb shell dumpsys deviceidle force-idle`): the service starts, with no `ForegroundServiceStartNotAllowedException` in logcat.
- [ ] Swipe the notification away with the app in the background: the re-post works, with no FGS start exception.
