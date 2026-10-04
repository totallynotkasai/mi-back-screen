# Mi Back Screen

Show your own images, animated GIFs, a clock and your notifications on the **Xiaomi 17 Pro Max** rear display.

**Website:** https://totallynotkasai.github.io/mi-back-screen/
**Download:** [latest APK](https://github.com/totallynotkasai/mi-back-screen/releases/latest/download/MiBackScreen.apk)

## Features

- Images and looping GIFs, chosen with the system photo picker
- Gallery: pick several images or a folder and it changes image every 1 minute to 1 day, in order or shuffled
- Works with no images too: the back screen is black, with the clock if it's on. **Remove images** clears the app's copies or lets go of the folder
- Scaling: Fill, Fit, Stretch or None (actual size, centred)
- Panning: images that don't fit the screen's shape glide slowly up and down or side to side, at Slow, Medium or Fast. It only moves while the back screen is lit
- Optional clock (time and date) over the wallpaper, in five styles: drag it anywhere in the preview or align it to an edge; any text colour, or Auto to stand out from the image; optional background colour and opacity
- The clock stays on time while the phone sleeps, including on the dimmed back screen
- Charging animation: when you plug in, the back screen lights up and plays a short animation with the battery level (wired or wireless), then fades back to the wallpaper. It stays dark while the back is covered, and can also play over Xiaomi's back screen while the wallpaper is off
- Notifications: a new one slides in at the top of the back screen for a few seconds, showing as much as you choose: just the app (Discreet), who it's from (Normal) or the message too (Full). Pull down from the top for the ones you haven't cleared. While the phone is locked it follows your lock screen, so sensitive content stays hidden if that's how your lock screen is set. With the wallpaper off, Xiaomi's own back screen shows notifications instead
- Refresh button to put the wallpaper up afresh if something didn't update
- Preview shaped like the rear display, with an option to keep clear of the camera
- Quick Settings tile and home-screen widget to toggle it
- Schedules to turn it on and off at set times (e.g. off overnight), as many as you like
- Background service keeps the wallpaper on the rear display, and puts it back after the phone restarts as soon as Shizuku is running again
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
6. For notifications: turn on **Show notifications on back screen** on the Notifications tab. Shizuku allows Notification access in the same tap. Without Shizuku, allow it in Settings; Android blocks that for sideloaded apps until you open App info → ⋮ → **Allow restricted settings**.

## Building

Requires JDK 17 and the Android SDK.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Unit tests run with `./gradlew testDebugUnitTest`.

## How it works

HyperOS doesn't let normal apps draw on the rear display, so Mi Back Screen uses Shizuku to run a fixed set of `am` / `input` commands (see [`RearCommands.kt`](app/src/main/java/com/backscreen/wallpaper/core/RearCommands.kt)):

- **Putting the wallpaper up.** It's launched straight onto the rear display while the phone is unlocked. While it's locked, HyperOS only allows its own apps there, so the wallpaper is opened on the main display and its task moved to the rear.
- **Keeping it there.** Each time the rear dims, Xiaomi's back screen app brings its own launcher back and closes whatever was on top, but only if that launcher has been opened since Xiaomi's app started. So when the wallpaper goes up over Xiaomi's launcher, Xiaomi's app is restarted **once**; it comes straight back without the launcher and leaves the wallpaper alone. If anything closes the wallpaper anyway, it's put back. (Up to 1.3 the app stopped Xiaomi's app every time, which relit the rear about every 10 seconds.)
- **Switching off** brings Xiaomi's launcher back.
- **Charging.** The animation is drawn inside the wallpaper window that's already on the back screen, so it appears at once. With the wallpaper off, that window goes up over Xiaomi's screen just for the animation (only if you allow it). The back screen is only lit for it when it's dark, the back isn't covered, and the main screen isn't turned sideways: HyperOS treats a back-screen wake then as an accident and covers it with its own "Press the Power button" screen.
- **Notifications.** A notification listener (Notification access, which Shizuku grants with `cmd notification allow_listener`) passes new notifications to the wallpaper window, which shows them as a banner at once. It skips ongoing ones, media players, downloads in progress, group summaries, silent ones, anything Do Not Disturb holds back, and the app you're using. The back screen is lit for a new one at most once a minute per app, not while you're using the phone or the back is covered; on a dimmed back screen the banner just appears, without lighting it.
- **The clock.** A dimmed back screen keeps showing its last frame, and the phone sleeps between minutes. So while the clock is on the back screen and that screen is lit or dimmed, an exact alarm wakes the phone briefly at each minute, the new time is drawn, and a short draw wake lock sends the frame to the dimmed panel, as the system's own always-on displays do. While the back screen is off (for example when the back is covered), there's no alarm; the clock catches up as soon as it wakes.

## Battery

Measured on a 17 Pro Max (HyperOS 3.0.319), unplugged and locked for an hour with the clock showing on the dimmed back screen almost the whole time: the app used an estimated **1.5 mAh**, about 0.02% of the battery. That's 46 one-minute wake-ups of about a second each, and the battery level didn't drop a percent. Every clock update arrived within 1.5 seconds of the minute.

Panning only moves while the back screen is lit; a dimmed back screen shows its last frame and costs nothing extra. Measured with the back screen held lit (three 5-minute runs each way, extrapolated): panning at Medium cost the app about **20 mAh per lit hour**, 0.3% of the battery, against about 1 mAh with it still. That's about 5 mAh a day if the back screen is lit for 15 minutes in all.

The charging animation runs for 3.5 seconds once per plug-in, using under a second of processor time; lighting the back screen for it keeps it lit for about 16 seconds.

## Privacy

No internet permission and no storage permission. Only the images or folder you pick are shared with the app; chosen images are copied into the app's private storage, and **Remove images** deletes those copies. Nothing leaves the phone.

Notifications are read only once you allow Notification access, and only while the Notifications section is on. Their text is read on the phone, shown on the back screen, kept in memory only while the notification is in your shade, and never stored or sent anywhere. **Remove access** on the Notifications tab takes the access away again.

Not affiliated with Xiaomi.
