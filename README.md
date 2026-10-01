# Mi Back Screen

Show your own images, animated GIFs and a clock on the **Xiaomi 17 Pro Max** rear display.

**Website:** https://totallynotkasai.github.io/mi-back-screen/
**Download:** [latest APK](https://github.com/totallynotkasai/mi-back-screen/releases/latest/download/MiBackScreen.apk)

## Features

- Images and looping GIFs, chosen with the system photo picker
- Gallery: pick several images or a folder and it changes image every 1 minute to 1 day, in order or shuffled
- Works with no images too: the back screen is black, with the clock if it's on. **Remove images** clears the app's copies or lets go of the folder
- Scaling: Fill, Fit, Stretch or None (actual size, centred)
- Optional clock (time and date) over the wallpaper, in five styles: drag it anywhere in the preview or align it to an edge; any text colour, or Auto to stand out from the image; optional background colour and opacity
- The clock stays on time while the phone sleeps, including on the dimmed back screen
- Refresh button to put the wallpaper up afresh if something didn't update
- Preview shaped like the rear display, with an option to keep clear of the camera
- Quick Settings tile and home-screen widget to toggle it
- Schedules to turn it on and off at set times (e.g. off overnight), as many as you like
- Background service keeps the wallpaper on the rear display
- No internet permission, no storage permission

## Requirements

- Xiaomi 17 Pro Max on HyperOS (Android 13+)
- [Shizuku](https://shizuku.rikka.app/), running and authorised for Mi Back Screen

## Setup

1. Install and start Shizuku (wireless debugging or ADB).
2. Install the APK and open Mi Back Screen.
3. Tap **Allow access** (or enable it in Shizuku → Authorized apps).
4. Pick images (or a folder) and turn on **Show on back screen**.
5. For schedules and the clock: if the app shows **Setup needed**, allow alarms, and in Settings → Apps → Mi Back Screen turn on **Autostart** and set **Battery saver** to **No restrictions**.

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Unit tests run with `./gradlew testDebugUnitTest`.

## How it works

HyperOS doesn't let normal apps draw on the rear display, so Mi Back Screen uses Shizuku to run a fixed set of `am` / `input` commands (see [`RearCommands.kt`](app/src/main/java/com/backscreen/wallpaper/RearCommands.kt)):

- **Putting the wallpaper up.** It's launched straight onto the rear display while the phone is unlocked. While it's locked, HyperOS only allows its own apps there, so the wallpaper is opened on the main display and its task moved to the rear.
- **Keeping it there.** Each time the rear dims, Xiaomi's back screen app brings its own launcher back and closes whatever was on top, but only if that launcher has been opened since Xiaomi's app started. So when the wallpaper goes up over Xiaomi's launcher, Xiaomi's app is restarted **once**; it comes straight back without the launcher and leaves the wallpaper alone. If anything closes the wallpaper anyway, it's put back. (Up to 1.3 the app stopped Xiaomi's app every time, which relit the rear about every 10 seconds.)
- **Switching off** brings Xiaomi's launcher back.
- **The clock.** A dimmed back screen keeps showing its last frame, and the phone sleeps between minutes. So while the clock is on the back screen and that screen is lit or dimmed, an exact alarm wakes the phone briefly at each minute, the new time is drawn, and a short draw wake lock sends the frame to the dimmed panel, as the system's own always-on displays do. While the back screen is off (for example when the back is covered), there's no alarm; the clock catches up as soon as it wakes.

## Battery

Measured on a 17 Pro Max (HyperOS 3.0.319), unplugged and locked for an hour with the clock showing on the dimmed back screen almost the whole time: the app used an estimated **1.5 mAh**, about 0.02% of the battery. That's 46 one-minute wake-ups of about a second each, and the battery level didn't drop a percent. Every clock update arrived within 1.5 seconds of the minute.

## Privacy

No internet permission and no storage permission. Only the images or folder you pick are shared with the app; chosen images are copied into the app's private storage, and **Remove images** deletes those copies. Nothing leaves the phone.

Not affiliated with Xiaomi.
