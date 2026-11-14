# Binge Guard

A personal Android app that tracks how long you spend in chosen apps and blocks long binge
sessions, while still allowing short check-ins through the day.

Android 17 (API 37) only: `minSdk = targetSdk = compileSdk = 37`. Sideloaded, not on the Play Store.
Kotlin, Jetpack Compose, Room, Gradle Kotlin DSL.

The name lives in one place: `app_name` in `app/src/main/res/values/strings.xml`.

## Building and installing

Open the folder in Android Studio, or from a terminal with the phone plugged in over adb:

```
scripts/install.sh            # debug build, installed over the top and started
scripts/install.sh --release  # release build
```

Command-line builds need a full JDK (`javac`). Fedora's default Java is a JRE, so the script
uses a JDK that Gradle downloaded into `~/.gradle/jdks` when `JAVA_HOME` doesn't have one.

### Signing

Debug and release builds are both signed with one stable key, so every build installs over the
previous one and keeps its data. The key is **not** in the repo:

- `~/.android/binge-guard-release.jks`: the key itself.
- `keystore.properties` (git-ignored, project root): its path, alias and password.

Back both up. Without them, the next build can't update the installed app; you'd have to
uninstall it first, which deletes its history and settings. If `keystore.properties` is missing,
the build falls back to the machine's debug key.

## Permissions

The onboarding screen walks through each one; it opens again from **Permissions** on the home screen.

| Permission | Why | How it's granted |
|---|---|---|
| Usage Access (`PACKAGE_USAGE_STATS`) | Read app resume/pause events | Settings > Usage access |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | The block screen, and sending you home | Settings > Display over other apps |
| `POST_NOTIFICATIONS` | The monitor's notification and the nudge | Runtime dialog |
| `FOREGROUND_SERVICE_SPECIAL_USE` | The monitor service | Install time |
| `QUERY_ALL_PACKAGES` | The app picker sees every app | Install time |
| Battery: Unrestricted | Keep the monitor alive and let it restart | System dialog |

### Why the foreground service type is `specialUse`

Checked against the Android 17 docs (behavior changes for all apps and for apps targeting 17,
foreground service types, timeouts and background-start restrictions):

- No foreground service type describes watching app usage. `specialUse` is the documented type for
  valid uses no other type covers.
- `specialUse` has no runtime prerequisite and no timeout. `dataSync` (the usual misfit) is limited
  to 6 hours per 24 and, since Android 15, can't be started from `BOOT_COMPLETED`; `specialUse` isn't
  on that list.
- The `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` text in the manifest is only reviewed by Google Play, which
  doesn't apply to a sideload, but it's filled in anyway.
- Android 17 lists no changes to foreground service types, overlays, UsageStatsManager,
  `QUERY_ALL_PACKAGES` or battery exemptions. It does add per-app memory limits that can kill a
  long-running process, which is why the service restarts itself (see Enforcement).

## Tracking

`MonitorService` (a `specialUse` foreground service) asks `UsageStatsManager.queryEvents` for new
events once a second while the screen is on (every 5 s while it's off). `ForegroundTracker` (plain
Kotlin, unit-tested) turns resume/pause, screen and lock-screen events into "the package on screen".
While a tracked app is on screen, `IntervalRecorder` writes it to Room as an interval, saving the
open interval's end every 5 s, so a killed process loses at most 5 s. On start the service replays
the last 30 min of events, so a restart mid-session picks up where it was.

Apps are keyed by package name, so a stock and a modded build are separate entries. The picker
shows every launchable app with its package name and version. **Detect current app** finds the
last app you used (other than Binge Guard and the launcher): open the app, come back, tap Detect.

### adb shortcuts for testing

```
adb shell appops set com.tony3974.bingeguard GET_USAGE_STATS allow
adb shell appops set com.tony3974.bingeguard SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.tony3974.bingeguard android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +com.tony3974.bingeguard
```
