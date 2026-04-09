# OLGX Roads

**Open Local Government eXchange - Roads**

An open-source Android-first platform for collecting, processing, and publishing crowd-validated road roughness and surface condition data for Australian councils.

## Project status

Early development / architecture phase.

This repository is the starting point for the **OLGX Roads** collector application and supporting documentation. The first implementation target is a **native Android application in Kotlin** that records road trip sensor data, stores it locally, and supports calibration and validation workflows for road roughness estimation.

### Android app (Phase 0 / 1)

The `app/` module is an Android Studio–compatible Gradle project (Kotlin DSL + version catalog) using **Jetpack Compose (Material 3)**, **Hilt**, **Room**, and **DataStore**. The current milestone includes:

- minimal **Home**, **Settings**, and **Diagnostics / DB health** screens (`org.openlgx.roads`)
- Room schema for passive-collection direction (sessions, samples, upload batches, placeholders)
- separate **capture policy** vs **upload policy** in settings (`AppSettings`)

**Phase 2A (passive collection lifecycle skeleton)**

Phase 2A adds the **runtime foundation** for passive collection (collector state machine, activity-recognition gateway with graceful degradation, coordinator mailbox, and a **foreground service skeleton** with notifications). It **does not** start continuous IMU/GNSS sampling, roughness scoring, road matching, uploads, or cloud sync.

Key implementation notes:

- Collector lifecycle states: `IDLE`, `ARMING`, `RECORDING`, `COOLDOWN`, `PAUSED_POLICY`, `DEGRADED` (`org.openlgx.roads.collector.lifecycle`)
- Activity recognition uses Google Play services when available; the UI and Diagnostics surface support/permission/update state
- Settings: passive toggle is persisted under DataStore key **`passive_collection_enabled`** (see `AppSettingsRepositoryImpl`)
- Foreground recording uses **location**-type FGS (`CollectorForegroundService` in `AndroidManifest.xml`); notification permission applies on Android 13+
- Debugging: enable **Debug mode** in Settings to reveal manual/simulation controls on Home

When debugging compile/Hilt/Room issues, check the manifest, `PassiveCollectionCoordinator`, and `README` build commands first.

**Phase 2B1 (fused GNSS capture in passive recording)**

End-to-end passive **RECORDING** now creates a `RecordingSession` (source **`AUTO`** for the normal passive path), starts **fused** location updates under a **foreground service of type `location`** (`CollectorForegroundService`), and persists batched rows to `location_samples` (elapsed realtime, UTC time, lat/lon, speed, bearing, accuracy, altitude when present; eligibility placeholders retained).

- **Arming gate**: Before leaving `ARMING`, the app requires a fused **speed** fix at or above Settings → capture min speed (high-accuracy current location call). Debug “simulate driving” still skips this gate so local testing stays easy.
- **Lifecycle**: `PassiveCollectionCoordinator` stops/flushes `SessionLocationRecorder` before finalizing the Room session row (complete / interrupt / policy abort). `SESSION` upload state remains `NOT_QUEUED` until a later upload phase.
- **Permissions**: `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, plus existing AR + notifications as needed.

**Phase 2B2 (IMU / motion capture in the same recording session)**

While the collector is **RECORDING** and the `CollectorForegroundService` is running, the app also captures **raw** motion samples into `sensor_samples`, sharing the same `RecordingSession` as GNSS (no separate sensor-only sessions).

- **Architecture**: `SensorGateway` + `SystemSensorGateway` (`SensorManager`) with explicit `SensorAvailabilitySnapshot`; `SensorCaptureConfig` controls delay, batch size, flush interval, and which sensor types to enable. Default **`enableRotationVector` is false** (persist full quaternion later if needed; `SensorSampleEntity.w` is ready).
- **Pipeline**: `SessionSensorRecorder` (`SensorRecordingController`) listens on a dedicated **HandlerThread**, buffers `SensorSampleEntity` rows in memory, and flushes via **`insertAll`** on a timer, when the buffer is full, on **batch signals**, and on **stop** (no per-callback DB I/O).
- **Lifecycle**: `SensorRecordingController.startRecording(sessionId)` / `stopAndFlush()` mirror location; called from `CollectorForegroundService` alongside `LocationRecordingController`; `PassiveCollectionCoordinator` invokes `sensorRecordingController.stopAndFlush()` whenever it stops location before ending a session.
- **Provenance**: `RecordingSessionEntity.sensorCaptureSnapshotJson` stores hardware availability, subscribed sensors, config snapshot, and degraded flags (accel/gyro required when enabled in config).
- **UI**: Home and Diagnostics show availability, capture active flag, sample counts, last timestamps, estimated callback rate, and degraded IMU state.

**Passive collection when the app is not in the foreground**

- **Activity recognition** results are delivered to a **manifest-registered** receiver (`ActivityRecognitionUpdatesReceiver`), not only a dynamically registered one, so the process can be started or woken when Google Play Services has an update (for example after the task was removed from recents and the OS had killed the app).
- **Device reboot**: `BootCompletedReceiver` handles `BOOT_COMPLETED` (with `RECEIVE_BOOT_COMPLETED`) so `RoadsApplication` / `PassiveCollectionHandle.start()` runs again and the coordinator can re-subscribe when passive collection is enabled.
- **While recording**: Trip capture still relies on **`CollectorForegroundService`** (location-type FGS); users should see an ongoing notification during recording.
- **Force stop** (Settings → Apps → *Force stop*): Android **does not** allow the app to restart background components until the user launches the app again. This is platform policy—not something the app can override.
- **OEM battery restrictions**: For best results on aggressive devices, set the app’s battery usage to **Unrestricted** (wording varies by manufacturer).

**Phase 2C (session inspection, local export, validation tooling)**

Browse **Recorded sessions** from Home; each row opens **Session detail** with id/uuid, timing, source, sample counts, last GNSS/IMU timestamps, `sensorCaptureSnapshotJson` / `collectorStateSnapshotJson`, upload/quality placeholders, and lightweight **validation** (monotonic wall clocks, coarse gap heuristics, accel/gra presence, low-rate warning). **Export session** writes a versioned bundle under `<externalFiles>/olgx_exports/` (folder + sibling `.zip`): `session.json`, `manifest.json`, `location_samples.csv` + `.json`, `sensor_samples.csv` + `.json`. The manifest includes `exportSchemaVersion`, `exportMethodVersion`, disclaimer, embedded **device profile** (if a `device_profiles` row exists), **validationSummary**, and (schema v2+) **`calibrationWorkflowEnabled`** / **`calibrationLiteraturePointer`** reflecting Settings. Diagnostics shows export root path, last export path/time/success/error, and DB-wide data-quality hints.

**Build / test (from repo root)**

| Goal | Command | APK output |
|------|---------|------------|
| Debug APK | `.\gradlew.bat :app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Signed release APK | `.\gradlew.bat :app:assembleRelease` (requires signing; see below) | `app\build\outputs\apk\release\app-release.apk` |
| Unit tests | `.\gradlew.bat :app:testDebugUnitTest` | — |

**Prerequisites:** Android SDK; **JDK 17** (Android Studio’s bundled runtime is fine); `local.properties` with `sdk.dir` (Android Studio creates this on first open).

**Windows — `JAVA_HOME is not set`:** Gradle needs `JAVA_HOME`. If you use Android Studio, point it at the bundled JBR for the current PowerShell session, then run `gradlew`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest
```

Or use the wrapper script (probes the same path and sets `JAVA_HOME` if unset):

```powershell
.\scripts\run-gradle.ps1 :app:testDebugUnitTest
```

To persist for your user account: System Properties → Environment Variables → **User** `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` (adjust if Studio is installed elsewhere).

**Emulator workflow (unchanged)**

- Full setup and troubleshooting: [docs/android-dev-setup.md](docs/android-dev-setup.md)
- Environment check: `.\scripts\check-android-env.ps1`
- Install debug build on an AVD and optionally launch the app:

  ```powershell
  .\scripts\dev-android.ps1 -Avd "YOUR_AVD_NAME" -LaunchApp
  ```

  (`scripts\dev-android.bat` is a thin wrapper that calls the same PowerShell script.)

**Real phone workflow (APK sideload, no Play Store)**

- Guide: [docs/android-real-device-testing.md](docs/android-real-device-testing.md)
- Local release signing only (no secrets in git): [docs/android-release-signing.md](docs/android-release-signing.md)

```powershell
.\scripts\build-debug-apk.ps1
.\scripts\install-debug-apk.ps1 -LaunchApp

.\scripts\build-release-apk.ps1
.\scripts\install-release-apk.ps1 -LaunchApp

.\scripts\list-android-devices.ps1
```

**Temporary Home activation (field testing)**  
Until a full onboarding flow ships, **Home** includes:

- A **Finish setup** card when onboarding is incomplete; **Complete onboarding & enable passive collection** writes to DataStore (`onboarding_completed` and ensures `passive_collection_enabled` is on).
- A **Passive collection status** card: onboarding, user toggle, effective passive, required runtime permissions, and whether the **AR pipeline is listening** (`activityRecognitionUpdatesActive` while passive is effective).
- **Permission** actions for activity recognition (Android 10+), fine location, and post notifications (Android 13+). They are available **before** onboarding so you can grant in any order; with **Debug mode** on (Settings), those buttons stay tappable for re-request attempts.
- **Debug tools** (Debug mode): complete or reset onboarding, simulate driving, force recording, reset collector state.

Signed **release** builds fail fast if signing is not configured. Use `keystore.properties` (copy from `keystore.properties.example`) or `ROADS_STORE_FILE`, `ROADS_STORE_PASSWORD`, `ROADS_KEY_ALIAS`, `ROADS_KEY_PASSWORD`.

## Vision

OLGX Roads aims to give Australian councils a practical, low-cost way to access road condition intelligence using commodity smartphones and open-source tooling.

The long-term goal is to build an open ecosystem where:

- community contributors and council fleets can collect repeat road condition data
- the platform aggregates and validates repeated traversals over the same road segments
- councils receive GIS-ready outputs for maintenance planning, asset management, and prioritisation
- the broader OLGX ecosystem can later expand into other open-source engineering tools for local government

## Problem statement

Road roughness and localised surface defects matter for:

- maintenance prioritisation
- road user comfort and safety
- asset deterioration tracking
- works planning
- network-level condition intelligence

Traditional roughness collection methods can be expensive, intermittent, and difficult for smaller councils to scale across large rural networks.

Modern smartphones contain motion and location sensors that make them a promising low-cost data source. However, raw phone data is noisy and highly affected by variables such as:

- device model
- mount position
- vehicle type and suspension
- travel speed
- tyre pressure
- driver behaviour
- lane position and wheelpath
- sealed versus unsealed surface
- potholes, corrugations, and isolated shocks
- sensor sampling variability across devices

This project exists to turn that noisy raw data into a disciplined, reproducible, and open engineering workflow.

## Core product concept

A user installs the Android app, explicitly starts a recording session, and drives a road segment. During the trip the app records:

- location
- speed
- heading
- accelerometer data
- gyroscope data
- derived gravity / linear acceleration / orientation signals where available
- timestamps and device metadata

The collected data is then:

1. stored locally on-device
2. reviewed through a debug and calibration workflow
3. exported or uploaded for later processing
4. matched to road segments
5. aggregated across repeat traversals
6. converted into roughness and anomaly outputs
7. published for councils as GIS-ready datasets

## Important technical stance

This project does **not** begin by claiming that a smartphone directly measures official IRI.

Instead, the early roadmap is:

- **Phase 1:** capture clean, structured motion and location data
- **Phase 2:** derive a robust **proxy roughness score**
- **Phase 3:** calibrate that proxy against labelled segments or profiler-derived ground truth
- **Phase 4:** develop a defensible **predicted IRI** model

This distinction is critical. The app should initially be treated as a **road response collector and roughness estimation platform**, not a direct replacement for a certified profiler.

## MVP goals

The first release of the Android app should support:

- Kotlin native Android app
- Jetpack Compose UI
- explicit trip start / stop workflow
- foreground trip recording service
- sensor capture
- GPS capture
- local database persistence
- per-trip summary screen
- calibration/debug screen
- export of trip data for offline analysis
- settings screen
- device diagnostics screen
- initial roughness feature extraction
- anomaly candidate detection
- documentation and engineering notes

## First implementation boundary

Version 0.1 should focus on **one thing only**:

> Collect high-quality, timestamped motion and location data during an explicitly started trip, and save it in a structured way that supports later calibration and road roughness modelling.

This means the app should not try to solve everything at once.

The first build does **not** need:

- user accounts
- cloud auth
- production backend
- council portal
- live GIS feeds
- iPhone support
- polished branding
- perfect IRI conversion
- public background tracking without explicit trip control

## Android-first strategy

The first implementation target is Android because it is the fastest path to:

- local field testing
- sideloaded builds
- iteration across multiple real devices
- early sensor validation
- mount and vehicle comparison
- controlled calibration runs

Initial test devices will include multiple Android phones mounted in cars travelling similar roads. This is intended to support:

- inter-device comparison
- normalization experiments
- mount sensitivity testing
- repeat-run stability checks

## Data capture principles

The collector should preserve enough information to support later scientific and engineering analysis.

### Raw signals
Capture:

- accelerometer
- gyroscope
- optional gravity sensor
- optional linear acceleration sensor
- optional rotation vector
- GNSS / fused location
- speed
- bearing
- horizontal accuracy
- altitude if available
- timestamps at source resolution

### Session metadata
Capture:

- device model
- Android version
- app version
- sampling configuration
- trip start/end time
- explicit trip notes
- mount orientation selection
- vehicle profile selection
- test route label
- sealed/unsealed tag if manually supplied

### Derived features
Compute and persist:

- vertical acceleration estimate
- RMS acceleration windows
- peak counts
- jerk measures
- gyro roll/pitch/yaw summaries
- speed-normalized features
- segment/window summary metrics
- anomaly candidate timestamps and scores

## Initial roughness methodology

The app should support an initial engineering workflow based on **proxy roughness**, not direct claimed IRI.

### Phase A: capture
Record motion and location reliably.

### Phase B: clean and normalize
Perform:

- timestamp ordering
- sensor gap checks
- noise filtering
- gravity separation
- orientation handling
- speed filtering
- stationary / walking rejection
- invalid GPS rejection

**Confounders** (cornering vs pavement vs designed discontinuities): **[analysis/ROUGHNESS_CONFOUNDERS.md](analysis/ROUGHNESS_CONFOUNDERS.md)**; offline prototypes in **[analysis/roughness_lab/](analysis/roughness_lab/)**; future council geo masks: **[specs/geo_exclusion_mask.schema.json](specs/geo_exclusion_mask.schema.json)**.

### Phase C: windowing
Group data into windows such as:

- time-based windows
- distance-based windows
- later road-segment-aligned windows

### Phase D: feature extraction
Generate candidate roughness indicators from:

- vertical acceleration RMS
- peak vertical response
- jerk
- frequency band energy
- roll/pitch motion
- event density per unit distance
- speed-normalized variability

### Phase E: scoring
Create an interpretable early score such as:

- normalized roughness score 0-100
- anomaly candidate score
- confidence flag for data quality

### Phase F: calibration
Later relate the proxy score to:

- repeated traversals
- labelled reference road sections
- known smooth/rough comparison roads
- eventual profiler-derived IRI or accepted council condition labels

Engineering background (standards, two calibration layers, literature): [docs/calibration-notes.md](docs/calibration-notes.md).

## Validation philosophy

The system should be built to answer these questions:

- Does the same phone on the same route produce stable outputs?
- Do different phones on the same route produce similar outputs after normalization?
- How much do mount position and vehicle type affect the response?
- How much does speed affect the response?
- Can repeated runs identify known rough spots consistently?
- Can the platform separate isolated shocks from generally rough pavement?
- Can the proxy score later be calibrated to predicted IRI or maintenance categories?

## Proposed road output types

Long term, the platform should support two main GIS outputs.

### 1. Road roughness segment layer
Per road segment:

- segment identifier
- roughness score
- predicted IRI when calibration exists
- confidence score
- run count
- distinct device count
- last observed date
- quality flags

### 2. Surface anomaly layer
For candidate point events such as:

- pothole-like impacts
- sharp bumps
- bridge joints
- corrugation clusters
- washouts or edge break indicators on unsealed roads

## Privacy and ethics

The project should be privacy-conscious from day one.

Principles:

- no secret background surveillance
- explicit trip control
- clear user disclosure
- minimal personally identifying data
- raw traces retained only as needed
- derived engineering outputs preferred over exposing user traces
- no publication of personal travel history

## Repository structure

A recommended structure for this repository is:

```text
docs/
  architecture/
  validation/
  calibration/
  gis/
  decisions/

app/
  (Android application)

backend/
  (future placeholder only)

scripts/
  export/
  analysis/
  calibration/

specs/
  api/
  schema/
  road-segment-model/
  geo_exclusion_mask.schema.json
  examples/
    exclusion_mask.example.geojson