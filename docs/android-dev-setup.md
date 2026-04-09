# Android developer setup (Windows-first)

This document explains how to run **OLGX Roads** on an **Android emulator** from the command line.

The scripts **do not** install Android Studio or the SDK for you. They detect common setups and print **clear Missing:** / **[ERROR]** messages when something is not installed.

## Prerequisites

You need:

1. **Android SDK** with:
   - **Android SDK Platform-Tools** (`adb`)
   - **Android Emulator** (`emulator.exe`)
2. **At least one AVD** (Android Virtual Device)
3. **JDK 17** available to Gradle (often via Android Studio’s JBR, or `JAVA_HOME`)

### Typical SDK location on Windows

- `%LOCALAPPDATA%\Android\Sdk` (Android Studio default)

The scripts look for the SDK in this order:

- `%ANDROID_HOME%`
- `%ANDROID_SDK_ROOT%`
- `%LOCALAPPDATA%\Android\Sdk`
- `%USERPROFILE%\AppData\Local\Android\Sdk`

The chosen folder must contain **both**:

- `platform-tools\adb.exe`
- `emulator\emulator.exe`

### Environment check only

From the repo root:

```powershell
.\scripts\check-android-env.ps1
```

This verifies `adb`, `emulator`, and `gradlew.bat`.

## Create an emulator (AVD)

You do **not** need a separate “huge GUI install” beyond what Android development already requires. The standard path is:

1. Install **Android Studio** (if you have not already).
2. Open **Device Manager** (Virtual Device Manager).
3. **Create Device** → pick a phone profile → pick a **system image** (API 33+ recommended) → finish.

Alternatively, advanced users can create AVDs with `avdmanager` from the command-line tools; the script does not run `avdmanager` for you.

## Run install on emulator (main workflow)

From the repo root, using **PowerShell** (recommended):

```powershell
.\scripts\dev-android.ps1 -Avd "YOUR_AVD_NAME"
```

Or with auto-launch after install:

```powershell
.\scripts\dev-android.ps1 -Avd "YOUR_AVD_NAME" -LaunchApp
```

Using **cmd.exe**:

```bat
scripts\dev-android.bat -Avd "YOUR_AVD_NAME" -LaunchApp
```

### AVD selection rules

- Pass **`-Avd "Name"`** explicitly if you have more than one AVD.
- If you have **exactly one** AVD, you may omit `-Avd` and the script will pick it automatically.
- If you have **multiple** AVDs and omit `-Avd`, the script prints the list and exits with instructions.

### List your AVD names

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

(Adjust the path if your SDK is elsewhere.)

## What the script does

1. Verifies SDK tools and `gradlew.bat`.
2. Validates / selects the AVD.
3. Starts `emulator @AvdName` in a **separate process** if no emulator is already visible to `adb`.
4. Waits for `adb` and for `sys.boot_completed=1`.
5. Runs `.\gradlew.bat :app:installDebug`.
6. Optionally launches the app using `adb shell monkey` targeting the launcher category (package parsed from `app/build.gradle.kts`).

The script sets **`ANDROID_SERIAL`** to the first `emulator-*` device it finds so Gradle installs to the right device when multiple `adb` devices are connected.

## Manual troubleshooting

### `adb` not found

- Install **SDK Platform-Tools** and ensure your SDK path is correct.
- Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to the SDK root.

### No AVDs / emulator won’t start

- Create an AVD in Android Studio **Device Manager**.
- Ensure the **Android Emulator** package is installed in **SDK Manager**.

### Gradle / Java errors

- Open the project once in Android Studio so it can create `local.properties` with `sdk.dir=...`.
- Ensure JDK 17 is used. If `JAVA_HOME` is not set, add it (Android Studio’s JBR is a common choice).

### Install goes to the wrong device

- Disconnect extra devices or set `ANDROID_SERIAL` yourself before running Gradle.
- The helper script forces `ANDROID_SERIAL` to the first `emulator-*` serial for the Gradle step.

### Launch step

- **Implemented:** `-LaunchApp` uses `adb shell monkey -p <applicationId> -c android.intent.category.LAUNCHER 1` with `<applicationId>` parsed from `app/build.gradle.kts`.
- If parsing fails, the script logs a warning and skips launch.

## Physical Android phones (sideload)

For **USB / manual APK install** on real devices—without the Play Store—see [android-real-device-testing.md](android-real-device-testing.md) and [android-release-signing.md](android-release-signing.md).

## Security note: ExecutionPolicy

If PowerShell blocks scripts, run **once per session**:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
```

The `.bat` wrapper uses `-ExecutionPolicy Bypass` for that process only.
