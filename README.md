# HFP Music Router

Android app for older cars that support Bluetooth calls through HFP/SCO but do not support Bluetooth media audio.

## Download

[**Download the latest APK release**](https://github.com/lerdeljan17/HfpMusicRouter/releases/latest/download/HfpMusicRouter.apk)

The APK is built and published automatically by GitHub Actions after every update to `main`.

## What it does

1. Connect your phone to the car's hands-free Bluetooth connection.
2. Open HFP Music Router and tap **Start device audio routing**.
3. On most phones, accept Android's screen/audio capture confirmation.
4. Play audio from an eligible local player, browser, YouTube, or another media app.
5. Tap **Stop routing** when finished.

## Galaxy S25 compatibility

Version 1.5 keeps the Galaxy S25 family (S25, S25+, S25 Edge and S25 Ultra) on a direct HFP path with no MediaProjection capture and no replay of the source audio.

Testing showed two Samsung-specific behaviors: the S25 already sends ordinary media into the HFP/SCO route, so replaying captured audio causes an echo; and a bare off-call SCO connection can be closed by the phone after roughly 10–20 seconds when there is no active communication stream.

To address both issues, v1.5 uses Android's modern `setCommunicationDevice()` routing API and keeps a silent `USAGE_VOICE_COMMUNICATION` AudioTrack active. The silent track contains no copy of the music; its only purpose is to keep the communication/HFP session alive. The original YouTube/local-player stream is therefore the only audible music stream on S25.

Galaxy S22 and other devices continue to use the playback-capture and replay path because those devices do not automatically mirror normal media into SCO.

## Important limitations

- HFP/SCO is designed for calls, so output is mono and phone-call quality.
- On non-S25 devices, Android playback capture only works for apps that permit their audio to be captured.
- DRM or protected content may be silent on the capture/replay path.
- Device manufacturers can implement audio routing differently, which is why the S25 uses a separate direct mode.
- Android 10 or newer is required for playback capture on devices that use the capture/replay path.

## Releases

The workflow publishes `HfpMusicRouter.apk` as the repository's **Latest release**. You can also browse all versions on the repository's Releases page.
