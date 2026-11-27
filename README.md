# Calendar

Android calendar app for a Nextcloud calendar. Layout inspired by Google Calendar; theme and design language inspired by T3 Code.

The app reads and writes the phone's calendar provider and never talks to Nextcloud itself. [DAVx5](https://www.davx5.com/) syncs the provider with Nextcloud. Any other calendar account on the phone shows up too.

Features: Schedule, Day, 3-day, Week and Month views; create, edit and delete events, including recurring ones; reminders with snooze; search; event colors; week start and 12h/24h settings. See [`docs/design.md`](docs/design.md).

## Install

1. Install DAVx5 and add your Nextcloud account.
2. Download the APK from [Releases](../../releases) and open it on the phone.

Android 8.0 (API 26) or newer.

## Build

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest lintDebug
```

Needs JDK 17+ and the Android SDK.

## Release

Push a `v*` tag, for example `git tag v1.0.0 && git push origin v1.0.0`. The `Release` workflow builds a signed APK and attaches it to a GitHub Release for that tag. The tag sets `versionName`; the workflow run number sets `versionCode`.

Signing uses these Actions secrets:

| Secret              | Value                                 |
|---------------------|---------------------------------------|
| `KEYSTORE_BASE64`   | the release keystore, base64-encoded  |
| `KEYSTORE_PASSWORD` | keystore password                     |
| `KEY_ALIAS`         | key alias                             |
| `KEY_PASSWORD`      | key password                          |
