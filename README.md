# HFP Music Router

Android app for older cars that support Bluetooth calls through HFP/SCO but do not support Bluetooth media audio.

## Download

[**Download the latest APK release**](https://github.com/lerdeljan17/HfpMusicRouter/releases/latest/download/HfpMusicRouter.apk)

The APK is built and published automatically by GitHub Actions after every update to `main`.

## What it does

1. Connect your phone to the car's hands-free Bluetooth connection.
2. Open HFP Music Router and tap **Start device audio routing**.
3. Play audio from YouTube, Spotify, a local player, browser, or another media app.
4. Tap **Stop routing** when finished.

On most phones the app uses Android playback capture and replays one mono stream over HFP/SCO. Android will show the screen/audio capture confirmation in that mode.

## Galaxy S25 compatibility

Version 1.4 uses a completely different path on the Galaxy S25 family (S25, S25+, S25 Edge and S25 Ultra).

Testing showed that these phones can already send the source app's original media stream to the car when the off-call SCO connection is opened. Replaying a MediaProjection capture therefore creates a second delayed copy in the same car speakers. On an S25, version 1.4 does **not** start MediaProjection, AudioRecord or AudioTrack at all. It only opens the HFP/SCO connection and lets Samsung's own audio policy route the original media stream.

Galaxy S22 and other devices keep the capture/replay path that works on those phones.

## Important limitations

- HFP/SCO is designed for calls, so output is mono and phone-call quality.
- The Galaxy S25 direct mode depends on Samsung's current audio-routing behavior and may vary by firmware version.
- On non-S25 devices, Android playback capture only works for apps that permit their audio to be captured.
- DRM or protected content may be silent in capture mode.
- Some phones or car Bluetooth modules reject SCO unless they believe a communication session is active.
- Android 10 or newer is required for playback-capture mode.

## Releases

The workflow publishes `HfpMusicRouter.apk` as the repository's **Latest release**. You can also browse all versions on the repository's Releases page.
