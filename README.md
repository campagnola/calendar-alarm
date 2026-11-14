# Calendar Alarm

An Android app that monitors calendar events from multiple sources and fires alarm notifications before they start. Supports ICS calendar URLs, local device calendars, flexible event filters, and quiet hours.

## Features

- **Multi-source calendars** — subscribe to any number of ICS URLs alongside local device calendars
- **Configurable advance alarms** — fire a notification N minutes before each event starts
- **Smart day view** — shows today's schedule; automatically switches to tomorrow's view after a configurable evening cutoff
- **Event filters** — include/exclude events and override alarm timing based on title, location, organizer, attendees, or calendar source
- **Quiet hours** — consolidate overnight alarms into a single summary notification fired at the start of the quiet period
- **Snooze** — snooze alarms with intelligent timing (larger of a minimum duration or 75% of remaining time to the event)
- **Meeting links** — detects Zoom and Microsoft Teams URLs in events and shows a one-tap Join button
- **Recurring events** — full RRULE support (daily/weekly/monthly/yearly with BYDAY, BYMONTHDAY, COUNT, UNTIL, EXDATE)
- **Efficient polling** — respects HTTP ETags and Last-Modified headers to minimise data usage
- **Survives reboots** — alarms are restored automatically after device restart
- **Optional alarm sound** — any system alarm sound, starting after a delay and fading in to a maximum volume (a percentage of the device's alarm volume)


## Getting an ICS Link

### Google Calendar

1. Open [calendar.google.com](https://calendar.google.com) in a browser.
2. In the left sidebar, hover over the calendar you want to share and click the three-dot menu → **Settings and sharing**.
3. Scroll down to **Integrate calendar**.
4. Copy the **Secret address in iCal format** link (ends in `.ics`).

> Use the *Secret address* link, not the public one, to include private events.

### Outlook / Microsoft 365

1. Open [outlook.live.com](https://outlook.live.com) or Outlook on the web (Microsoft 365).
2. Click the calendar icon in the left rail, then click the **gear icon** (Settings) → **View all Outlook settings** → **Calendar** → **Shared calendars**.
3. Under **Publish a calendar**, choose the calendar and permission level, then click **Publish**.
4. Copy the **ICS** link that appears.

For on-premises Exchange or Outlook desktop: go to **File → Account Settings → Account Settings → Internet Calendars** to find the calendar URL, or ask your IT admin for the ICS feed URL.


## Installation

**Requirements:** Android Studio (Hedgehog or later), Android SDK 26+, a device or emulator running Android 8.0+.

1. **Clone the repository**
   ```
   git clone <repo-url>
   cd calendar_app
   ```

2. **Open in Android Studio**
   - Launch Android Studio.
   - Choose **File → Open** and select the `calendar_app` directory.
   - Wait for Gradle sync to finish (first sync downloads dependencies and may take a minute).

3. **Build and run**
   - Connect an Android device via USB (enable *USB debugging* in Developer Options) or start an emulator.
   - Click the **Run** button (▶) or press **Shift+F10**.
   - Select your device and click **OK**.

4. **Grant permissions**
   On first launch the app will request:
   - *Calendar* — to read local device calendars
   - *Notifications* and *Exact alarms* — to fire alarm alerts
   - *Display over other apps / full-screen intents* — to show alarms over the lock screen

   All three are required for full functionality.


## Settings Reference

Open **Settings** (gear icon in the top-right of the main screen).

### Alarm

| Setting | Default | Description |
|---|---|---|
| Advance warning | 5 min | How many minutes before an event to fire the alarm |
| Poll interval | 15 min | How often to refresh ICS sources |
| Minimum snooze | 4 min | Shortest snooze duration |
| Show tomorrow at | 20:00 | After this time the main view switches to tomorrow's events |

### Quiet Hours

Events that start during quiet hours are suppressed individually and instead included in a consolidated summary notification fired at the start of the quiet period.

| Setting | Default | Description |
|---|---|---|
| Start | 22:00 | Beginning of quiet hours |
| End | 07:00 | End of quiet hours |
| Summary alarm | On | Fire the consolidated summary at quiet-hours start |

### Calendar Sources

Tap **Manage Sources** to add, remove, or reload calendars.

**ICS URL** — paste a calendar feed URL (see [Getting an ICS Link](#getting-an-ics-link) above). The app auto-detects the calendar name from the feed. Each source shows its last-fetched status and today's event count.

**Local device calendar** — enable the toggle to read from calendars already on the device. Tap individual calendars to include or exclude them.

### Filters

Filters let you control which events appear in the main view, which trigger alarms, and how far in advance those alarms fire.

Filters are evaluated in order. All criteria in a filter must match for its actions to apply. When multiple filters match the same event, later filters override earlier ones.

#### Criteria

Each filter can have zero or more criteria (an empty criteria list matches every event):

| Criterion | Description |
|---|---|
| Source | Match events from a specific calendar (ICS URL or local calendar) |
| Event name | Match against the event title |
| Location | Match against the event location field |
| Organizer | Match against the event organizer/sender |
| Attendees | Match against any attendee in the event |

String criteria support these operators (all case-insensitive):

- **Contains** / **Does not contain**
- **Equals** / **Does not equal**

#### Actions

Each filter can apply one or more actions:

| Action | Options | Description |
|---|---|---|
| Visibility | Show / Hide | Whether the event appears in the main view |
| Alarm | Enable / Disable | Whether an alarm notification fires for the event |
| Advance time | *N* minutes | Override the global advance warning for matching events; can be relative to event start or event end |
| Quiet hours override | Silent / Early / Alarm | Override quiet-hours behaviour for matching events |

#### Examples

- **Silence social calendar overnight** — Source = *Personal*, Action: Alarm = Disable
- **Extra notice for standup** — Name contains "standup", Action: Advance = 10 min
- **Hide declined meetings** — Attendees contains your email + response = declined, Action: Visibility = Hide


## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for the full text.
