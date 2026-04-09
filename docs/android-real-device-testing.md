# Real Android device testing (USB + sideload, no Play Store)

This guide covers **field testing on physical phones** using **debug** or **signed release** APKs. It does **not** use the Play Store.

For **emulator** workflow, see [android-dev-setup.md](android-dev-setup.md) and `scripts\dev-android.ps1`.

## Prerequisites (Windows)

- **Android SDK Platform-Tools** (`adb`) on `PATH` or under `%ANDROID_HOME%` / `%LOCALAPPDATA%\Android\Sdk`
- **USB debugging** enabled on the phone (Developer options)
- **USB cable**; accept “Allow USB debugging” on the device when prompted
- **JDK 17** for Gradle (see README)

Quick check:

```powershell
.\scripts\check-android-env.ps1
```

For **install scripts** only, an emulator package is **not** required—`scripts\list-android-devices.ps1` uses **adb-only** detection.

## Package name and launch

| Item | Value |
|------|--------|
| Application ID | `org.openlgx.roads` |
| Launch activity | `org.openlgx.roads/.MainActivity` |

**Launch after install** (optional):

```powershell
adb shell am start -n org.openlgx.roads/.MainActivity
```

Install scripts support `-LaunchApp` to do this automatically.

## List connected devices

```powershell
.\scripts\list-android-devices.ps1
```

Or:

```powershell
adb devices -l
```

Use the **serial** column when multiple devices show `device` (not `unauthorized` / `offline`).

## Build APK outputs

| Variant | Gradle task | Output path (repo-relative) |
|---------|-------------|-------------------------------|
| **Debug** | `:app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| **Release** (signed) | `:app:assembleRelease` | `app\build\outputs\apk\release\app-release.apk` |

**Debug vs release (practical):**

- **Debug**: debuggable, signed with the auto **debug** keystore; good for rapid iteration; do not ship to untrusted users as “production”.
- **Release**: not debuggable by default; **must** be signed with **your** release keystore (see [android-release-signing.md](android-release-signing.md)); use for a small trusted field-trial ring.

## Scripted build + install (recommended)

From repo root:

```powershell
# Debug
.\scripts\build-debug-apk.ps1
.\scripts\install-debug-apk.ps1 -LaunchApp

# Release (requires keystore.properties or ROADS_* env vars)
.\scripts\build-release-apk.ps1
.\scripts\install-release-apk.ps1 -LaunchApp
```

**Multiple devices:**

```powershell
.\scripts\install-debug-apk.ps1 -DeviceSerial "R58Mxxxxxx" -LaunchApp
```

## Manual adb install (USB)

1. Build the APK (or copy a built `app-*.apk` to the PC).
2. Connect the phone; run `adb devices` and confirm `device`.
3. Install or upgrade **in place** (same app id; release builds need **same signing key** for `-r` upgrade):

```powershell
adb install -r path\to\app-debug.apk
```

Path may be:

- `app\build\outputs\apk\debug\app-debug.apk`
- `app\build\outputs\apk\release\app-release.apk`

With several devices:

```powershell
adb -s SERIAL install -r path\to\app.apk
```

## Manual install without adb (copy APK to phone)

1. Copy the APK to the phone (USB file transfer, cloud, etc.).
2. On the phone, open the APK from **Files** / **Downloads**.
3. If prompted, allow **Install unknown apps** for that source (browser, Files, etc.) — wording varies by OEM/Android version.
4. For **updates**, open the **new** APK the same way; Android replaces the app if the **signing certificate matches**. If it does not, uninstall the old app first (**data loss** unless backed up).

## Troubleshooting

| Symptom | What to try |
|---------|-------------|
| `adb devices` shows `unauthorized` | Unlock phone; accept RSA fingerprint; revoke USB debugging authorizations and reconnect. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | New APK signed with a **different** key than installed app. Uninstall old app (clears data) or sign with original key. |
| `INSTALL_PARSE_FAILED` / corrupt | Re-copy APK; rebuild; ensure download completed. |
| Multiple devices | Use `-s SERIAL` on every `adb` command or disconnect extras. |
| Install script says multiple devices | Run `.\scripts\list-android-devices.ps1` and pass `-DeviceSerial`. |

## Uninstall (optional)

Removes the app and **all local data**:

```powershell
adb uninstall org.openlgx.roads
```

## Where exports go (Phase 2C reminder)

On-device exports use app storage under **external app files**; Diagnostics shows the path. That is independent of how you installed the APK.

## What this doc intentionally omits

- **Play Store** publishing and **internal testing** tracks (future).
- **OTA** / backend-driven updates.
