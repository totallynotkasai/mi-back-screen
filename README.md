# Back Screen

Show your own image or animated GIF on the **Xiaomi 17 Pro Max** rear display.

**Website:** https://totallynotkasai.github.io/back-screen-wallpaper/
**Download:** [latest APK](https://github.com/totallynotkasai/back-screen-wallpaper/releases/latest/download/BackScreen.apk)

## Features

- Images and looping GIFs, chosen with the system photo picker
- Preview shaped like the rear display, with an option to keep clear of the camera
- Quick Settings tile and home-screen widget to toggle it
- Schedules to turn it on and off at set times (e.g. off overnight), as many as you like
- Background service keeps the wallpaper on the rear display
- No internet permission, no storage permission

## Requirements

- Xiaomi 17 Pro Max on HyperOS (Android 13+)
- [Shizuku](https://shizuku.rikka.app/), running and authorised for Back Screen

## Setup

1. Install and start Shizuku (wireless debugging or ADB).
2. Install the APK and open Back Screen.
3. Tap **Allow access** (or enable it in Shizuku → Authorized apps).
4. Pick an image or GIF and turn on **Show on back screen**.

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## How it works

HyperOS doesn't let normal apps draw on the rear display, so Back Screen uses Shizuku to run a fixed set of `am` / `input` commands (see [`RearCommands.kt`](app/src/main/java/com/backscreen/wallpaper/RearCommands.kt)): launch the wallpaper activity on the rear display, stop Xiaomi's rear launcher, and restore it when you switch off.

Not affiliated with Xiaomi.
