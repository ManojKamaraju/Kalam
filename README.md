# KALAM

A gesture-driven, hyper-customizable precision timer for Android 15 and newer (API 35+, including Android 16 and later).
No buttons, no toolbar, no settings screen: the screen is the interface.

Format `HH:MM:SS.mmm`, hours unlimited, stopwatch **and** countdown, per-number fonts/colours/outline, free positioning,
pinch scaling, rotation, 25 patterns, 25 GPU animated backgrounds, gallery wallpapers, per-number haptics, alarms,
background timing with notification, and complete state persistence.

## Get the APK (no Android Studio needed)

You build it for free on GitHub, from your PC browser:

1. Go to <https://github.com/new>, name the repository `KALAM`, choose **Private** (or Public), click **Create repository**.
2. On the new empty repository page click **uploading an existing file**.
3. Unzip `KALAM-source.zip` on your PC. Open the unzipped `KALAM` folder, press **Ctrl+A** to select everything inside it
   (make sure hidden items are shown so `.github` is included) and **drag it all into the GitHub upload page**.
   The project is deliberately under 100 files so the browser upload accepts it.
4. Click **Commit changes**.
5. Open the **Actions** tab. The workflow **Build KALAM APK** starts by itself (3 to 8 minutes).
   If it does not, open it and press **Run workflow**.
6. When it shows a green tick, open the run, scroll to **Artifacts** and download **KALAM-apk**. Unzip it: inside is `KALAM.apk`.
7. Copy `KALAM.apk` to your phone, open it and allow "Install unknown apps" when asked.

The APK is signed with the standard Android debug key, which is fine for installing directly on your own phone.

If the workflow turns red, open the failed step, copy the last 30 lines of its log and send them to me; most build problems are
one-line fixes.

### If the file upload misses the `.github` folder
Create it by hand: **Add file > Create new file**, type `.github/workflows/build-apk.yml` as the file name (the slashes create the folders),
paste the contents of that file from the zip, and commit.

### Using git or GitHub Desktop instead
Same result: put the folder in a repository and push it. Nothing else is needed.

## Build locally (optional)

Requirements: JDK 17, Android SDK 35, Python 3 with `fonttools`, git.

```
bash tools/fetch_fonts.sh      # downloads and subsets the bundled fonts (once)
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

## How to use it

| Gesture | Action |
|---|---|
| Tap the background (or a number) | Pause / resume |
| Double tap | Reset the four numbers to 00:00:00.000 (nothing else changes) |
| Triple tap | Restore your Master Layout (numbers untouched) |
| Scroll up / down over a number | Increase / decrease that number (faster scroll = bigger steps) |
| Hold a number briefly, then drag | Move that number anywhere |
| Pinch with fingers side by side (horizontal) | Scale the whole timer |
| Pinch with fingers one above the other (vertical) | Scale the nearest single number (fingers need not touch it) |
| Twist two fingers | Rotate the whole timer by any angle |
| Long-press a number | That number's menu: font, weight, style, outline, colours, rollover, haptics |
| Long-press the background | Settings: background, Master Layout, timer behaviour, persistence, background execution, notifications, gestures, appearance, haptics, alarms, advanced |
| Tap while an alarm rings | Stop the alarm |

Tip: tap with all zeros = stopwatch (counts up). Scroll any number = countdown (a running stopwatch immediately counts back down from the scrolled value; a paused one waits for a tap; it rings at zero).

## Android limits worth knowing

* Interval haptics while the phone is asleep can be delayed by the system's battery saver (Doze). The countdown alarm itself uses an
  exact alarm and is not affected.
* After a restart the timer is restored once you have unlocked the phone at least once (Android only delivers the restart signal then).
  If you "Force stop" the app in system settings, Android blocks all background work until you open it again.
* Vibration strength, duration and patterns depend on your phone's vibration motor.
* Notification permission is requested the first time a timer starts with background running enabled. Without it the timer still runs.

App icon: a hollow ring. Change its colour in long-press background > Advanced > App icon ring colour (10 colours; the launcher may take a few seconds to refresh).

Documentation: [Architecture](docs/ARCHITECTURE.md) | [Decisions and defaults](docs/DECISIONS.md) | [Testing](docs/TESTING.md)
