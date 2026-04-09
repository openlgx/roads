# Android release signing (field trials, no Play Store)

OLGX Roads **release APKs** used for real-phone field testing must be **signed** with your own keystore. Nothing in this repo contains secrets: configure signing **locally** only.

## What Gradle expects

The Android Gradle Plugin reads signing from:

1. **Optional file**: `keystore.properties` at the **repository root** (sibling of `settings.gradle.kts`).
2. **Environment variables** (optional override for each field). If a variable is set and non-empty, it **wins** over the file for that field.

| `keystore.properties` key | Environment variable      | Meaning |
|----------------------------|---------------------------|---------|
| `storeFile`                | `ROADS_STORE_FILE`        | Path to `.jks` / `.keystore` |
| `storePassword`            | `ROADS_STORE_PASSWORD`    | Keystore password |
| `keyAlias`                 | `ROADS_KEY_ALIAS`         | Signing key alias |
| `keyPassword`              | `ROADS_KEY_PASSWORD`      | Key password (often same as store) |

**Note:** `.\gradlew build` runs release tasks and therefore **requires** signing. For day-to-day work use `assembleDebug`, or configure signing first.

## Bootstrap from the example

1. Copy `keystore.properties.example` → `keystore.properties` (repo root).
2. Create or reuse a keystore (see below).
3. Fill in the four values. Use `/` or escaped paths in `storeFile`; absolute paths are fine, e.g. `C:/Users/you/.secrets/olgx-roads-release.jks`.
4. Confirm `keystore.properties` and `*.jks` / `*.keystore` are **ignored by git** (they are listed in `.gitignore`).

**Never commit** `keystore.properties`, keystore files, or passwords.

## Creating a keystore (one-time)

With JDK `keytool` (adjust alias and filenames):

```powershell
keytool -genkeypair -v `
  -keystore olgx-roads-release.jks `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -alias olgx_release
```

Store the keystore file somewhere **outside** the repo or under a path that is git-ignored. Point `storeFile` at it.

## If signing is missing

- Running `.\gradlew.bat :app:assembleRelease` or `bundleRelease` **without** all four values fails **before** release compile, with a message pointing here.
- `.\scripts\build-release-apk.ps1` also checks for a properties file or all four env vars and exits early with `[ERROR]`.

## Installing updates on testers’ phones

Later APKs **must** be signed with the **same** key as the first install, or Android will block upgrade (uninstall the old app first, which **deletes local data** — avoid if possible).

## Play Store (future)

For Google Play you will typically use **Play App Signing** and upload an AAB. That workflow is **not** in this repo yet; you will still use a local keystore (upload key) that Play documents separately.
