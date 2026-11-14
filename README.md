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

## Policy engine (`policy/`)

Plain Kotlin with no Android dependencies, unit-tested on the JVM:
`./gradlew :policy:test`. `PolicyEngine.evaluate` is stateless: it decides from an app's recorded
foreground intervals, its limits, the time, and any overrides, so it gives the same answer after
a restart.

| Rule | Default | Exactly |
|---|---|---|
| Session | gap < 2 min | Foreground intervals joined by gaps shorter than the gap. A session's length counts foreground time only, not the gaps. |
| Free hit | 5 min | A session up to this long gets no nudge and no cooldown. |
| Nudge | 6 min | A notification once per session. |
| Session cap | 8 min | Blocks. The session ends there. |
| Cooldown | × 1.5 | After a session longer than the free hit ends, the app is blocked for length × 1.5, counted from its last moment on screen. Coming back within the gap continues the session instead (toward the cap). |
| Rolling budget | 15 min per 60 min | A running session may use it all. A new session needs at least 1 min left, so a lifted block gives a real check-in. |
| Daily ceiling | 90 min | From local midnight. Same 1-minute rule for new sessions. Lifts at midnight. |
| Override | 30 s wait + phrase | Lets you in for 5 min despite any block. Logged. The overridden time counts as use, so the session and its cooldown grow. |

When several limits apply, the block screen shows the one that lasts longest. Time spent bouncing
off the block screen is never recorded, so prodding a blocked app doesn't extend its cooldown.

## Enforcement

Each second a tracked app is on screen, the monitor runs the policy engine for it:

- **Allow:** record the time.
- **Nudge:** record, and post one heads-up notification per session ("2 min left, leave now and
  the cooldown is 9 min").
- **Block:** stop recording, log a block event, show the block screen and send you to the home
  screen (again every 2 s if the app is still in front).

The block screen is a Compose view in a `TYPE_APPLICATION_OVERLAY` window. It shows why, a
countdown and today's use. **Close** (or back) dismisses it, leaving you on the home screen.
**I need it anyway…** starts the friction override: a 30 s wait (per-app), then typing the phrase
(`OVERRIDE_PHRASE` in `enforce/Policies.kt`). That logs an override event and opens the app for
the per-app grant (5 min). Starting the home screen from the service is allowed because Display
over other apps is still a background-activity-start exemption on Android 17. Without that
permission, a block can only post a notification.

Staying alive:

- `START_STICKY`: Android recreates the service after its process is killed. That restart counts
  as a background foreground-service start, which the battery exemption allows.
- `BootReceiver`: restarts it on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (installing an update
  kills the running process). Both broadcasts are exemptions for starting a foreground service,
  and `specialUse` isn't one of the types barred from boot.
- Nothing important is held only in memory. On restart the service replays the last 30 min of
  usage events, and the engine works from Room. The only thing that resets is "already nudged
  this session".

## Screens

- **Home:** each tracked app with today's total, a meter each for the day and the rolling window,
  and its state right now: available, mid-session (and how long you have to reopen it to
  continue), overridden, or blocked with a countdown. Blocked apps sort to the top. A banner
  names any missing permission. Tap an app for its settings.
- **App settings:** every limit for that app, the enforce switch, defaults, and stop tracking.
  Saved limits apply on the monitor's next tick.
- **History:** the last 14 days. Sessions are rebuilt from the recorded intervals, with whether
  each was a free hit, got a cooldown or hit the cap. Overrides show how long you waited and
  until when. Blocks show the reason.
- **Choose apps:** the picker (see Tracking).
- **Permissions:** the onboarding screen.

History is kept for 90 days.

## Layout

```
policy/          pure Kotlin policy engine + tests (no Android)
app/
  data/          Room: tracked apps and their limits, usage intervals, block and override logs
  monitor/       the foreground service, ForegroundTracker (+ tests), IntervalRecorder, BootReceiver
  enforce/       Room -> engine glue, the block overlay and its screen
  apps/          installed-app list, icons, "last used app"
  permissions/   permission checks and the Settings screens that grant them
  ui/            Compose screens
```

### adb shortcuts for testing

```
adb shell appops set com.tony3974.bingeguard GET_USAGE_STATS allow
adb shell appops set com.tony3974.bingeguard SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.tony3974.bingeguard android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +com.tony3974.bingeguard
```
