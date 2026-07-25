# HFP Music Router

Android proof-of-concept that plays a selected local audio file through a Bluetooth hands-free (HFP/SCO) connection, intended for older cars that support Bluetooth calls but not Bluetooth media audio.

## Build

GitHub Actions automatically builds a debug APK on every push to `main`.

Open **Actions → Build Android APK**, select the latest successful run, then download the `HfpMusicRouter-debug-apk` artifact.

## Limitations

- Audio is mono and telephone quality because HFP/SCO is designed for calls.
- Compatibility depends on the Android phone and car Bluetooth module.
- This app plays audio selected inside the app; it does not capture Spotify, YouTube, or other apps.
