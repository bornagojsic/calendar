# Calendar — Design Doc (draft v0)

Status: draft, under discussion. Open questions are marked **OPEN**.

## 1. Goal

A personal Android calendar app that:

- shows and edits events from a Nextcloud calendar,
- follows Google Calendar's layout and navigation,
- uses T3 Code's look: dark, neutral surfaces, one accent color, dense typography, and subtle borders instead of shadows.

Out of scope for v1: home screen widgets, light theme, tasks/to-dos, other users' calendars, a backend.

## 2. Data source

```
Nextcloud (CalDAV) <-> DAVx5 (sync adapter) <-> Android CalendarProvider <-> Calendar app
```

- The app never talks to Nextcloud directly. DAVx5 (free, available on F-Droid and Play) syncs CalDAV into the system `CalendarContract` provider.
- The app reads and writes `CalendarContract.Calendars`, `Events`, `Instances`, `Reminders` and `Attendees`.
- `Instances` expands recurring events, so the app never parses RRULEs to display events.
- Writes go to the provider. DAVx5 pushes them to Nextcloud on its next sync.
- Permissions: `READ_CALENDAR`, `WRITE_CALENDAR` and `POST_NOTIFICATIONS` (API 33+).
- Bonus: any other account on the device (Google, Exchange) works with no extra code.

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
- **Event detail**: a bottom sheet or full screen with title, time, recurrence, location, description, reminders, calendar, and edit/delete actions.
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

Dark only for v1. Dynamic color (Material You) is off.

## 6. Reminders and notifications

- The provider broadcasts `CalendarContract.ACTION_EVENT_REMINDER` when a reminder fires. The app registers a receiver and posts a notification from it.
- **Risk**: if another calendar app (for example Google Calendar or Etar) also handles this broadcast, the user gets duplicate notifications. The app will offer a setting to turn its own reminders off.
- Notification actions: open the event, snooze (5/10/30 min, through `AlarmManager`), dismiss.

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

## 10. Open questions

- **OPEN** Accent color: T3 Code stock accent, or a custom one?
- **OPEN** Week start day and 24h vs 12h: follow the system locale, or add a setting?
- **OPEN** App name and package id (for example `dev.<you>.calendar`).
- **OPEN** Distribution: sideload APKs from GitHub Releases, or also F-Droid/Play?
- **OPEN** Search: title only, or also location and description?
- **OPEN** Remove the duplicate-reminder risk by making this the only calendar app on the device?
