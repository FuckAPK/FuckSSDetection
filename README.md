# FuckSSDetection

An Xposed/LSPosed module to prevent apps from detecting screenshots and screen recordings.

## Features

- **Android 14+**: Blocks native `registerScreenCaptureObserver` and `ScreenRecordingCallbackController`. (Requires hooking the System Framework).
- **Android 13 & below**: Bypasses legacy detection methods that monitor file system or database changes. (Requires targeting specific apps).

## Usage

- For target **Android 14+**: Check the **System Framework (android)** is enough.
- For target **Android 13 & below**: Check the **specific applications** you want to bypass.
