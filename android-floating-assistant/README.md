# Voice Noter Floating Assistant

Independent Android overlay app that reuses the Voice Noter transcription backend while the user keeps Gboard, Samsung Keyboard, or any other keyboard active.

The existing PWA and `android-keyboard-companion` are not modified by this app.

## Features

- Movable round microphone bubble over other apps
- Small arrow menu for Record/Live mode, BN/EN/AR language, settings, and close
- Record-then-transcribe through `{baseUrl}/api/transcribe`
- Soniox live transcription through the existing temporary-key endpoint
- Accessibility-based insertion into the currently focused editable field
- Clipboard fallback when a field does not support accessibility paste
- Password fields are never edited
- Persistent foreground notification while the bubble is active

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/Voice-Noter-Floating-0.1.0-debug.apk
```

## First setup on the phone

1. Install and open **Voice Noter Floating**.
2. Tap **Allow microphone**.
3. Tap **Allow floating bubble** and enable display over other apps.
4. Tap **Enable text insertion**, select **Voice Noter text insertion**, and enable it.
5. Choose Record or Live, language, and backend endpoint.
6. Tap **Start floating assistant**.
7. Open any app, focus a text field, and tap the round bubble.
8. Tap again to stop and insert the transcript.

The Accessibility Service exists only to paste the generated transcript into the active editable field. If it is disabled or the target app rejects insertion, the transcript is copied to the clipboard.
