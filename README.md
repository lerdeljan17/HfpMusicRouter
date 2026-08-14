# HFP Music Router

Android app for older cars that support Bluetooth calls through HFP/SCO but do not support Bluetooth media audio.

## Download

[**Download the latest APK release**](https://github.com/lerdeljan17/HfpMusicRouter/releases/latest/download/HfpMusicRouter.apk)

The APK is built and published automatically by GitHub Actions after every update to `main`.

## What it does

1. Connect your phone to the car's hands-free Bluetooth connection.
2. Open HFP Music Router and tap **Start device audio routing**.
3. Accept Android's screen/audio capture confirmation.
4. Play audio from an eligible local player, browser, YouTube, or another media app.
5. Tap **Stop routing** when finished.

## Galaxy S25 compatibility

Version 1.3 adds a stronger single-stream workaround for the Galaxy S25 family (S25, S25+, S25 Edge and S25 Ultra). Some S25 firmware versions mirror the source app's normal `STREAM_MUSIC` audio into SCO while HFP Music Router is also replaying the captured audio, causing two delayed copies in the car.

While HFP routing is active on an S25, the app therefore temporarily mutes only the normal `STREAM_MUSIC` output and keeps its captured replay on `USAGE_VOICE_COMMUNICATION`. When routing stops, the app restores the previous music-stream mute state. Galaxy S22 and other devices continue to use the normal routing path.

## Important limitations

- HFP/SCO is designed for calls, so output is mono and phone-call quality.
- Android playback capture only works for apps that permit their audio to be captured.
- DRM or protected content may be silent.
- YouTube and similar apps may work depending on the Android version, phone manufacturer, and the source app's capture policy.
- Some phones or car Bluetooth modules reject SCO unless they believe a communication session is active.
- Android 10 or newer is required for device playback capture.

## Releases

The workflow publishes `HfpMusicRouter.apk` as the repository's **Latest release**. You can also browse all versions on the repository's Releases page.
