# Architecture

Package `com.timer.app`. No third-party runtime libraries: only the Android platform APIs (minSdk/targetSdk/compileSdk 35).

## The six engines (spec section 4) and where they live

| Engine | Files | Notes |
|---|---|---|
| Time Engine | `TimeEngine.kt`, `TimeFormat.kt` | Pure Kotlin. The value is always `base ± (now − anchor)` from `SystemClock.elapsedRealtimeNanos()` (monotonic). Nothing is accumulated per frame, so there is no drift. Rollover/borrow logic is in `Rollover`. |
| Gesture Engine | `GestureEngine.kt` | Pure Kotlin deterministic state machine (host + listener interfaces). Unit tested with a virtual clock and simulated fingers. |
| Appearance Engine | `Model.kt` (`ElementStyle`, `BackgroundConfig`), `Fonts.kt`, `Backgrounds.kt`, glyph code in `TimerView.kt` | Each of the four numbers owns an independent `ElementStyle`. Glyphs are cached as vector paths: fill, outline-only (real stroke on a contour-merged path) and fill+outline. |
| Motion Engine | `TimerView.kt` | Critically damped springs (ω = 15 rad/s) for layout changes and orientation swing; direct manipulation snaps to the finger (no lag). |
| Persistence Engine | `Storage.kt`, `Core.kt` (session) | Separate JSON files: `appearance`, `layout` (+Master Layouts), `background`, `haptics`, `alarm`, `prefs`, `session`. Versioned envelope `{schema, data}` with a migration hook (`Schema.migrate`). Atomic writes (`AtomicFile`); a corrupt file is renamed `*.corrupt` and defaults are used. |
| Background & Notification Engine | `TimerService.kt`, `Core.kt`, receivers | Foreground service (type `specialUse`) only while the timer runs in the background or an alarm rings. `AlarmManager` exact alarm for countdown end / stopwatch target. `BootReceiver` for restart recovery. |

`Core` (held by the `Application`) connects them through plain interfaces. Because it lives in the Application, receivers can use the timer with no UI.
Changing fonts, colours, background, orientation or layout never touches `TimeEngine`; resetting numbers never touches any configuration.

## Rendering (`TimerView`)

One custom `View`. Each frame: derive element targets from the model (`computeTargets`), advance springs, draw background, draw four elements.
Digits are drawn in fixed-width cells (widest digit of the font) so numbers never jitter. Hours grow as needed and the row/element auto-shrinks
so a value like `10000` is never clipped. Frames are requested only while something changes (timer running, animation, animated background),
paused + static background = zero redraws. Animated backgrounds are throttled to the chosen frame rate.

Hit regions are invisible rectangles around each number (at least 28 dp of padding), rotated and scaled with the element, so touch always follows the number.

## Gesture resolution rules

* Finger lands on a number and moves **before** the pick-up delay (180 ms): vertical scroll.
* Finger rests for the pick-up delay and then moves: drag.
* Finger rests (no movement) for the long-press delay (450 ms): menu.
* 1 / 2 / 3 taps are reported only when the tap window (280 ms) closes, or immediately on the third tap, so a triple tap never fires the double-tap reset.
* Two fingers: the first threshold crossed locks the gesture for its lifetime: twist (9°) → rotate; distance change (14 dp) with fingers side by side → global scale;
  with fingers stacked vertically → scale of the nearest number.

## Persistence precedence

| Setting combination | On cold start (process created) |
|---|---|
| Reset Timer When Reopening = on | Numbers reset to zero when the UI opens (unless the timer is running through a live background service). Always wins. |
| Remember = on | Numbers, mode, running state restored; elapsed time while closed is added if it was running. |
| Remember = off, Run in background = on, timer was running | Restored (background recovery). |
| otherwise | Numbers start at zero. |

Reset When Closing and Remember = off both zero the numbers on the **Close KALAM** action. Swiping the app away keeps a background-running timer alive;
if nothing is running it behaves like Close. Visual settings, Master Layout, haptics and alarms are never part of any reset (except the explicit "Reset all settings").

## Orientation

`fullSensor` (portrait, landscape, reverse portrait, reverse landscape); the Activity handles configuration changes itself so nothing is recreated.
Portrait-class and landscape-class screens keep separate layouts (+ separate Master Layouts). On a size change the visual state is scaled into the new frame
and springs to the new layout while the composition swings upright from the old device orientation.

## Alarm and haptics

* Haptics: per number independent interval (any value ≥ 0.02 s), intensity, duration, pattern. Event *k* fires when session progress crosses *k × interval*.
  In countdown mode events beyond the countdown's duration are never scheduled. Edits and resume re-base the schedule; missed events while asleep are skipped, never bursted.
* Alarm: 8 built-in synthesized tones (`res/raw`), or a user audio file copied to private storage. Audio focus is requested, looping up to N seconds or until stopped,
  optional vibration. A missing custom file falls back to the default tone and the app asks for a replacement when opened.

## Privacy and permissions

`VIBRATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. Wallpapers and audio use the system pickers and are copied
into app-private storage; no storage permission, no network permission, no account, no analytics.

## Generated assets (tools/)

* `make_fonts.py` / `fetch_fonts.sh`: subsets 85 OFL/Apache font families (268 files) from google/fonts; run automatically in CI.
* `make_patterns.py`: the 25 background patterns (procedural, generated at development time, shipped as WebP).
* `make_alarms.py`: synthesizes the 8 alarm tones. Backgrounds animations are AGSL shaders in `Backgrounds.kt`.
