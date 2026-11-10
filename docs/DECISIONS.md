# Open product decisions (spec section 37) and the defaults chosen

The spec asks that these are listed explicitly rather than invented silently. Each is easy to change; the code location is given.

| # | Question | Decision implemented | Where to change |
|---|---|---|---|
| 1 | Stopwatch, countdown or both? | **Both, with no mode switch.** Tap while all numbers are zero: stopwatch (counts up). Scroll any number: it becomes a countdown from zero plus the scrolled amount; if it was running it immediately counts back down, if paused it waits for a tap. A finished countdown (or double-tap reset) returns to zero, and the next tap at zero is a stopwatch again. | `TimeEngine.start`, `TimeEngine.edit` |
| 2 | What happens at zero? | Countdown stops at 00:00:00.000, the alarm rings (sound + vibration + notification), any tap stops it. A countdown cannot be started at zero. A stopwatch can also ring when it reaches a configurable target time. | `Core.runDue`, `TimerService` |
| 3 | Editing while running? | Allowed by default; switchable. The edited value continues running from the new value. | `AppPrefs.editWhileRunning` |
| 4 | Ranges | Milliseconds 0-999, seconds 0-59, minutes 0-59, hours unlimited (arithmetic is overflow-safe far beyond any realistic value). | `Rollover` |
| 5 | Separate rollover/borrow configuration? | Three independent switches (ms→s, s→m, m→h). Same rule for up and down. **On**: carry into / borrow from the next number (at 00:00:00.000 going down it clamps at zero). **Off**: the number wraps inside its own range and never touches the next. | `Rollover.stepOnce` |
| 6 | Rotation gesture | Two-finger twist. The first threshold crossed (9° twist or 14 dp distance change) locks the gesture so rotating never scales and scaling never rotates. | `GestureEngine` |
| 7 | Background menu while another menu is open? | Not simultaneously: tapping outside closes the panel first. Every number's menu has an **All settings** row that opens the full settings in the same panel. | `Menus.elementMenu` |
| 8 | Reset on closing vs. active background service | Reset on **Close KALAM** always applies. Swiping the app away keeps a running background timer alive (it was explicitly allowed to run in background); a stopped timer is treated like Close. Reset when reopening never resets a timer that is running through a live background service. | `Core.onActivityDestroyed`, `Core.onOpen` |
| 9 | Count time while the device was off? | Yes, by default (setting "Count time while device is off"). When off, the timer resumes from where it was at shutdown. | `Core.restoreSession` |
| 10 | Several Master Layout profiles? | One Master Layout per orientation class (portrait-like and landscape-like). The data model stores layouts as separate objects, so adding named profiles later does not change the file format. | `AppModel.masters` |

## Other choices

* "Close" is a menu action (Settings > Advanced > Close KALAM) and a notification action; the Back key moves the app to the background and never loses state.
* The launcher icon ring is light grey (`#9EA3A8`) so it stays visible on both light and dark wallpapers; change it in `res/drawable/ic_launcher_foreground.xml`.
* Font size is intentionally absent from menus (spec 9.6): size is changed with the vertical pinch.
* Fonts are a curated library of 85 open-licensed families in ten categories (not Microsoft fonts, which cannot be redistributed). "Outline" fonts such as Monoton and
  Bungee Outline are included, and any font can be shown outline-only through real stroked contours.

## Operating-system limitations (spec 35: stated with the closest valid alternative)

* Android may delay interval haptics while the phone is deeply asleep (Doze). Alternative implemented: haptics run while the process is alive (foreground service when background timing is on);
  the end-of-countdown alarm uses an exact `AlarmManager` alarm and fires regardless.
* Proprietary "Dynamic Island" features are not used (spec 26.4). Standard notifications with a system chronometer are used, which keeps counting without waking the app.
* The notification chronometer shows seconds (not milliseconds); the in-app display is millisecond-accurate.
* After a user "Force stop", Android blocks all background execution until the next manual launch; nothing can restore that.
