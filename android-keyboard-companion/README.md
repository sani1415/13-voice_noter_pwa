# Voice Noter Android Keyboard Companion

Native Android input method for using the existing Voice Noter transcription backend from any app.

The keyboard records microphone audio, sends it to:

```text
{your-pwa-base-url}/api/transcribe
```

and commits the returned Bengali text into the active input field.

## Can I Build This With Expo?

No. This companion is not an Expo app.

It is a native Android keyboard, and Android keyboards must be built with Android's `InputMethodService`. Expo managed apps cannot become a system keyboard by running an Expo command.

You can still build it without opening Android Studio. Use the Android SDK plus Gradle from the terminal.

## Terminal APK Build

If `gradle`, `gradlew.bat`, or `adb` is not recognized, read **Windows Toolchain Fix** below first.

### 1. Install prerequisites

Install these once:

- JDK 17
- Android SDK command-line tools

The easiest setup is still to install Android Studio once, because it installs the Android SDK. After that, you can build from terminal only.

### 2. Confirm Android SDK is available

PowerShell:

```powershell
$env:ANDROID_HOME
```

If it prints nothing, set it to your SDK path. Common Windows path:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
```

For a permanent user environment variable:

```powershell
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
```

Close and reopen the terminal after `setx`.

### 3. Add a Gradle wrapper

If `gradle` is installed globally, run this inside `android-keyboard-companion/`:

```powershell
gradle wrapper
```

After this, the project will have `gradlew.bat`, so future builds do not need global Gradle.

If you do not have global Gradle, open this folder once in Android Studio and let it sync; Android Studio can generate/use the Gradle setup for you.

### 4. Build debug APK

From this folder:

```powershell
cd android-keyboard-companion
.\gradlew.bat assembleDebug
```

APK output:

```text
android-keyboard-companion/app/build/outputs/apk/debug/app-debug.apk
```

### 5. Install APK on phone

Enable developer options and USB debugging on the phone, connect by USB, then:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

If `adb` is not found, it is usually here:

```text
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
```

## Windows Toolchain Fix

Your errors mean these tools are not installed or not in `PATH`:

- `gradle`: needed once to generate `gradlew.bat`
- `gradlew.bat`: project-local Gradle wrapper, created after `gradle wrapper`
- `adb`: Android Debug Bridge, installed with Android SDK Platform Tools

### Option A: easiest reliable path

1. Install Android Studio.
2. Open Android Studio once.
3. Go to **Settings > Languages & Frameworks > Android SDK > SDK Tools**.
4. Install:
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools
   - Android SDK Build-Tools
5. Install Gradle for Windows, or use Android Studio once to open this project and sync it.

After that, close and reopen PowerShell.

### Option B: command-line install with winget

Run PowerShell as normal user:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
winget install Google.AndroidStudio
winget install Gradle.Gradle
```

Then open Android Studio once and install SDK tools from SDK Manager.

Close and reopen PowerShell, then test:

```powershell
java -version
gradle -v
```

For `adb`, try the full path first:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

If that works, install the APK with the full path:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

### Build after fixing tools

From the project folder:

```powershell
cd D:\programming\13-voice_noter_pwa\android-keyboard-companion
gradle wrapper
.\gradlew.bat assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

If `gradle wrapper` still fails, open `android-keyboard-companion/` once in Android Studio and run the build from there.

## After APK Install

1. Make sure the main Voice Noter PWA backend is deployed and `/api/transcribe` works.
2. On the phone, open **Voice Noter Keyboard**.
3. The endpoint box should already show this base URL:

```text
https://notes.idarah786.com
```

If you edit it later, enter only the base URL. Do not enter:

```text
https://notes.idarah786.com/api/transcribe
```

4. Tap **Save endpoint**.
5. Tap **Grant microphone permission**.
6. Tap **Enable keyboard**.
7. In Android settings, enable **Voice Noter Keyboard**.
8. Android will show a keyboard privacy warning. For your own APK, accept it.
9. Go back to the app and tap **Switch keyboard**, then choose **Voice Noter Keyboard**.
10. Open any app, tap a text box, press **Start voice**, speak, then press **Stop**.

If transcription fails, first check that the phone can open this URL in a browser:

```text
https://notes.idarah786.com/api/transcribe
```

Opening it directly may show a method error because it expects POST, but the page should be reachable. If it cannot reach the URL, fix deployment/network first.

## Open In Android Studio

1. Open `android-keyboard-companion/` as an Android Studio project.
2. Let Android Studio sync Gradle.
3. Run the `app` configuration on a physical Android device.

A physical device is recommended because microphone and keyboard switching behavior are more reliable than on emulators.

## First Setup On Phone

1. Open **Voice Noter Keyboard** from the launcher.
2. Confirm the deployed PWA base URL:

```text
https://notes.idarah786.com
```

Do not include `/api/transcribe`; the keyboard adds that path itself.

3. Tap **Grant microphone permission**.
4. Tap **Enable keyboard** and enable **Voice Noter Keyboard** in Android's keyboard settings.
5. Tap **Switch keyboard** and select **Voice Noter Keyboard**.

Android will show a warning when enabling any custom keyboard. That is normal for all third-party keyboards, because keyboards can read typed text. For private use, install only your own APK.

## Use

1. Open any app with a text field.
2. Switch to **Voice Noter Keyboard**.
3. Tap **Start voice**.
4. Speak Bengali.
5. Tap **Stop**.
6. The transcript is inserted into the active field.

Other keyboard controls:

- **Delete** removes selected text first. If nothing is selected, it removes one character.
- Hold **Delete** to keep deleting continuously.
- **All** selects all text in the current field; then tap **Delete** to clear it.
- Bangla letter rows are included for quick manual correction after voice typing.
- **Next** switches back to another installed keyboard.

## Local Backend Testing

If testing against `vercel dev`, expose the dev server to the phone on the same Wi-Fi network and use a URL like:

```text
http://192.168.1.25:3000
```

HTTPS production deployment is preferred. This prototype allows cleartext HTTP so local phone-to-PC testing works; lock this down before publishing outside private use.

## Current Scope

- Native Android `InputMethodService`
- Microphone recording as AAC/M4A
- POST JSON body compatible with the existing `/api/transcribe`
- Inserts returned `text` into the current input connection
- Keyboard controls: start/stop, Bangla letters, vowel marks, space, selection-aware delete, hold-to-delete, punctuation, enter, select all, next keyboard

This is intentionally separate from the PWA and does not change the existing web app.

## Password Note

The keyboard does not log in to the notes app. It only sends audio to `/api/transcribe`.

If the password is the normal Voice Noter app login/PIN, that should not block transcription. If the whole domain is protected by browser-level password, Cloudflare Access, Vercel Deployment Protection, or HTTP Basic Auth, the keyboard will not automatically inherit that browser login. In that case, either allow `/api/transcribe` through that protection or add an API token flow to the keyboard and backend.
