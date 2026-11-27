# Calendar — Design Doc (draft v1)

Status: draft v1. Decisions in §10.

## 1. Goal

A personal Android calendar app that:

- shows and edits events from a Nextcloud calendar,
- follows Google Calendar's layout and navigation,
- uses T3 Code's look: dark, neutral surfaces, one accent color, dense typography, and subtle borders instead of shadows.

Out of scope for v1: home screen widgets, light theme, tasks/to-dos, other users' calendars, a backend.

## 2. Data source

```
Nextcloud (CalDAV) <-> built-in sync adapter (or DAVx5) <-> Android CalendarProvider <-> Calendar app
```

- The views, search and reminders only read and write the system `CalendarContract` provider: `Calendars`, `Events`, `Instances`, `Reminders` and `Attendees`. Every calendar account on the phone (DAVx5, Google, Exchange, the app's own) shows up with no extra code.
- `Instances` expands recurring events, so the app never parses RRULEs to display events.
- Writes go to the provider. The account's sync adapter pushes them to the server.

### Built-in CalDAV sync (`sync/`)

- The app registers its own account type (`dev.bornagojsic.calendar`), an authenticator and a calendar sync adapter, the way DAVx5 does. Settings → Accounts adds an account (server URL, username, app password; the password lives in `AccountManager`) and removes one after a confirm dialog, which deletes its calendars from the phone and leaves the server alone.
- Discovery: `.well-known/caldav` (else the given URL) → `current-user-principal` → `calendar-home-set` → calendars. Only calendars that hold `VEVENT`s are synced. Name, color and read-only state come from the server.
- Libraries: `dav4jvm` (WebDAV/CalDAV, from JitPack) and `ical4j` (iCalendar).
- Provider columns: a calendar's `_SYNC_ID` is its collection URL and `CAL_SYNC1` its ctag. A series master's `_SYNC_ID` is the resource name, `SYNC_DATA1` its etag and `SYNC_DATA2` the iCalendar last seen. Exceptions are rows with `ORIGINAL_SYNC_ID`.
- Each sync, per calendar: push first, then pull.
  - Push: dirty or deleted rows, grouped by series, are uploaded as one resource (master plus exceptions) with `If-Match` on the etag, or `If-None-Match: *` for new ones. Cancelled occurrences become `EXDATE`s. Properties the provider doesn't hold (attendees, categories, …) are copied over from `SYNC_DATA2`.
  - Conflicts: server wins. A 412 re-downloads the server's copy over the local change.
  - Pull: skipped when the ctag is unchanged. Otherwise the etags of all events are listed, changed ones fetched with `calendar-multiget`, and ones gone from the server deleted.
- Mapping (`ICal.kt`): `DTSTART`'s `TZID` becomes `EVENT_TIMEZONE`; all-day events are UTC midnights; recurring rows get `DURATION`, others `DTEND`; `EXDATE`/`RDATE` are stored as UTC; `RECURRENCE-ID` events become exception rows; `VALARM`s triggered before the start become `Reminders`.
- Timing: periodic sync every 15 minutes by default (Settings → Sync every), an upload right after each local edit, and pull-to-refresh (which syncs every account on the phone).

- Permissions: `READ_CALENDAR`, `WRITE_CALENDAR`, `POST_NOTIFICATIONS` (API 33+), plus `INTERNET` and the sync settings/stats permissions for the built-in sync.

## 3. Stack

| Concern        | Choice                                                           |
|----------------|------------------------------------------------------------------|
| Language       | Kotlin                                                           |
| UI             | Jetpack Compose, Material 3 with custom color/type/shape tokens  |
| Min / target   | minSdk 26, targetSdk latest stable                               |
| Architecture   | Single activity, MVVM: `ViewModel` + `StateFlow`                 |
| DI             | Manual (one `AppContainer`). Add Hilt only if it hurts.          |
| Navigation     | Navigation Compose                                               |
| Async          | Coroutines + Flow. Provider changes come from a `ContentObserver` wrapped in `callbackFlow`. |
| Date/time      | `java.time`                                                      |
| Settings       | DataStore (Preferences)                                          |
| Build          | Gradle Kotlin DSL + version catalog                              |
| CI             | GitHub Actions: build + unit tests + lint                        |

## 4. UI layout (after Google Calendar)

- **Top app bar**: hamburger, month/year title (tap to open a mini month picker), "today" button, search, overflow.
- **Navigation drawer**: view switcher (Schedule / Day / 3 days / Week / Month), a calendar list with per-calendar visibility checkboxes and color swatches, and Settings.
- **FAB**: new event.
- **Views**
  - *Schedule*: infinite vertical list grouped by day. Empty days collapse. Today pinned on launch.
  - *Day / 3-day / Week*: an all-day strip on top and a 24h time grid below. Overlapping events split into columns. A current-time line. Pinch to zoom the hour height. Swipe horizontally to page.
  - *Month*: 6x7 grid with up to N event chips per cell plus "+k". Tap a day to open Day view.
- **Event detail**: bottom sheet that opens half-height and can be dragged up to full screen (like Google Calendar). Shows title, time, recurrence, location, description, reminders, calendar, and edit/delete actions.
- **Event editor**: full screen with title, all-day toggle, start/end, time zone, recurrence (presets plus custom), calendar, reminders, location, description, and color.
- Tap an empty slot in the time grid to create an event at that time.

## 5. Visual design (after T3 Code)

T3 Code traits to borrow:

- Near-black canvas with layered surfaces (`canvas` < `surface` < `surfaceRaised` < `surfaceOverlay`). Elevation comes from lighter tone, not shadow.
- 1dp hairline borders (`border`) separate regions.
- One saturated accent for primary actions, the today marker, selection and the current-time line.
- Muted secondary text (`textMuted`) and placeholder tones.
- Compact density, small radii (about 6-8dp), monospace for times and numbers.
- Lucide-style thin outline icons.

Token mapping onto M3 `ColorScheme`:

| T3 role          | M3 slot                          | Used for                     |
|------------------|----------------------------------|------------------------------|
| canvas           | background                       | screen background            |
| surface          | surface / surfaceContainer       | grids, list                  |
| surfaceRaised    | surfaceContainerHigh             | sheets, drawer, cards        |
| surfaceOverlay   | surfaceContainerHighest          | menus, dialogs               |
| text             | onSurface / onBackground         | primary text                 |
| textMuted        | onSurfaceVariant                 | secondary text, hour labels  |
| border           | outlineVariant                   | grid lines, dividers         |
| accent           | primary                          | FAB, today, now-line         |
| accentForeground | onPrimary                        | text on accent               |
| error            | error                            | destructive actions          |

Event colors come from the calendar color in the provider, toned down so they sit on a dark canvas. Use a fixed lightness and chroma per palette slot so chips stay legible.

Accent: `#346BF1` (blue). `onPrimary` is white.

Dark only for v1. Dynamic color (Material You) is off.

## 6. Reminders and notifications

- The provider broadcasts `CalendarContract.ACTION_EVENT_REMINDER` when a reminder fires. The app registers a receiver and posts a notification from it.
- This will be the only calendar app on the device, so no other app handles the broadcast and there are no duplicate notifications. No setting to turn reminders off.
- Notification: tap opens the event, one "Snooze 1h" action (through `AlarmManager`), swipe dismisses.

## 7. Module layout

Single `:app` module to start:

```
app/src/main/java/.../
  data/        CalendarRepository, provider queries, mappers
  domain/      Event, Calendar, Instance models; layout math (overlap columns)
  ui/theme/    Color.kt, Type.kt, Shape.kt, Theme.kt
  ui/schedule/ ui/day/ ui/week/ ui/month/ ui/event/ ui/drawer/
  reminders/   ReminderReceiver, NotificationHelper
```

Split into modules only if build times hurt.

## 8. Testing

- Unit: overlap/column layout, recurrence preset to RRULE strings, date range math.
- Instrumented: repository against the real provider on an emulator, using a local test account.
- Compose screenshot tests: later, maybe.

## 9. Milestones

1. Skeleton: project, theme tokens, CI, permissions flow, calendar list in the drawer.
2. Read-only views: Schedule, then Day/3-day/Week, then Month.
3. Event detail, create/edit/delete.
4. Reminders and notifications.
5. Polish: search, settings, app icon.
6. v1 release: signed APKs on GitHub Releases, event color in the editor, README.

## 10. Decisions

- **App name / package id**: Calendar, `dev.bornagojsic.calendar`.
- **Week start and 12h/24h**: follow the phone locale by default. Settings let you override each one: week start (locale / Monday / Sunday / Saturday) and time format (locale / 12h / 24h).
- **Distribution**: signed APKs attached to GitHub Releases only. A workflow builds and signs the release APK on a `v*` tag. The keystore and its passwords live in GitHub Actions secrets and are never committed.
- **Search**: matches title, location, description and attendee names/emails (case-insensitive `LIKE` on the `Events` table). Results are listed in Schedule-view style.
- **Reminders**: this is the only calendar app on the device (see §6).
- **Event color**: the editor offers the account's own palette (the provider's `Colors` table, which DAVx5 fills) and stores its key, so DAVx5 can sync it. Accounts without a palette get no color row.
