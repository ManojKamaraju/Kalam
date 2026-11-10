# Testing

## Automated (runs in CI on every build: `./gradlew testDebugUnitTest`)

39 JUnit tests, all passing when the project was assembled:

* `TimeEngineTest` (20): millisecond progression without drift (7000 ms in 1000 small steps), pause/resume, countdown finishing exactly once, no start at zero,
  numeric-only reset, every rollover relationship ON and OFF, increasing and decreasing, borrowing, clamping at zero, independent toggles, unbounded hours
  (up to 1,000,001 hours), editing while running, progress in both modes, recovery after process death (same boot), after restart with and without counting off-time,
  countdown that finished while the app was away.
* `GestureEngineTest` (14): single/double/triple tap resolution (a triple tap never fires the double-tap action), long press on background and number, scroll up/down,
  hold-then-drag vs scroll, horizontal pinch only scales everything, vertical pinch only scales one number and never scrolls, twist only rotates, gesture locking,
  second finger cancelling a pending scroll/tap.
* `TimeFormatTest` (5): `HH:MM:SS.mmm` formatting including 10,000 hours, digit padding, parsing of "90", "1:30", "1:02:03", "75.5", rejection of garbage.

## Verified during development (outside the phone)

* All Kotlin sources type-check against the Android 35 platform API.
* `AndroidManifest.xml` and all resources link with `aapt2` against the Android 35 framework.
* The 25 animated-background shaders compile and render (checked through WebGL, same shading language family as Android's AGSL).
* The font generation script regenerates the identical 85-family library from source.

## Manual checklist on a real phone (spec section 34; Android emulator/device required)

Gestures: single/double/triple tap, long press (background and each number), scroll both directions on each number, horizontal and vertical pinch (fingers not on text),
drag, rotation, at several screen locations and in several layouts.

Timer: ms progression, second/minute/hour rollover with each toggle on/off, decreasing values, 1000+ hours, 10 minute run compared with another clock.

Persistence: set a custom layout, change colours/fonts, start the timer, swipe the app away, reopen; verify numbers, running state, positions, visuals, for each
combination of Remember / Reset on reopening / Reset on closing / Run in background.

Orientation: portrait, landscape, reverse portrait, reverse landscape, with a running timer; no reset of values or layout.

Background: minimise, switch app, lock, unlock, return, swipe away, reopen, restart the phone, with background running on and off; notification Pause/Resume/Close buttons;
countdown alarm while the screen is off.

Alarm: each built-in tone, a custom audio file, delete the custom file then open the app (asks for a replacement), alarm while another app plays audio.

Backgrounds: all four categories; wallpaper in portrait, landscape, a huge photo, a corrupt file; delete a wallpaper then reopen (falls back to solid, other settings kept).

Performance: animated background + outline font + rotated layout + four haptics together stays smooth; battery use with a 30 fps animation.
