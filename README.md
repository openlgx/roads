# OLGX Roads

**Open Local Government eXchange - Roads**

An open-source Android-first platform for collecting, processing, and publishing crowd-validated road roughness and surface condition data for Australian councils.

## Project status

The app is now a **local-first Android collector plus experimental on-device analysis and review** (Room **v6**, heuristic roughness windows, exports with derived artifacts, optional **hosted alpha** upload queue and **road-pack GeoJSON parsing** + real **filtered export** trimming for uploads). It is **not** yet a full production networked product: **labelling/training pipelines, calibration to IRI, hardened production ops, and council rollout** remain future work—but **ingestion scaffolding** (Neon + Supabase Edge Functions + WorkManager upload) now exists in-tree.

**Supabase Edge env:** `supabase secrets set` rejects custom names starting with `SUPABASE_`; functions accept **`RAW_BUCKET` / `PUBLISHED_BUCKET`** plus auto-injected `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` (see [docs/setup-hosted-alpha.md](docs/setup-hosted-alpha.md)). Without Neon DSN on Edge (`DATABASE_URL` / `DATABASE_URL_POOLED`), `healthz` returns HTTP 503.

**Authoritative phased plan:** see [ROADMAP.md](ROADMAP.md). **Server shape (Neon metadata, Storage blobs, Edge Functions):** see [docs/backend.md](docs/backend.md). **Normative upload API:** [docs/api-contract.md](docs/api-contract.md). **Operator setup:** [docs/setup-hosted-alpha.md](docs/setup-hosted-alpha.md). **Single-council pilot — PowerShell command order:** [docs/pilot-readiness.md](docs/pilot-readiness.md) (migrations, seed, deploy, preflight, smoke, Android, processing, publish). **Neon migrations (pilot blockers):** apply `backend/sql/migrations/*.sql` in order on the branch your `DATABASE_URL` uses — `python backend/scripts/apply_neon_migrations.py` (loads `backend/.env.local`). Empty schemas fail upload Edge Functions at runtime. **Pilot go/no-go after deploy:** Supabase CLI from `backend/supabase` → `pilot_preflight.py` → `pilot_smoke_e2e.py` (exact sequence in [docs/pilot-readiness.md](docs/pilot-readiness.md)). **`seed_pilot_council.py`** validates the LGA boundary (Polygon/MultiPolygon, WGS84) and enforces the canonical pack path `roadpacks/<slug>/<version>/public-roads.geojson` so registration matches `build_road_pack.py` and the Android `filesDir` layout.

This repository centers on a **native Android application in Kotlin** that records road trip sensor data, processes trips on-device for screening/review, stores data locally, and exports bundles suitable for future upload and research workflows.

### Android app (Phase 0 / 1)

The `app/` module is an Android Studio–compatible Gradle project (Kotlin DSL + version catalog) using **Jetpack Compose (Material 3)**, **Hilt**, **Room**, and **DataStore**. The current milestone includes:

- minimal **Home**, **Settings**, and **Diagnostics / DB health** screens (`org.openlgx.roads`)
- Room schema for passive-collection direction (sessions, samples, upload batches, placeholders)
- separate **capture policy** vs **upload policy** in settings (`AppSettings`)

**Phase 2A (passive collection lifecycle skeleton)**

Phase 2A adds the **runtime foundation** for passive collection (collector state machine, activity-recognition gateway with graceful degradation, coordinator mailbox, and a **foreground service skeleton** with notifications). It **does not** start continuous IMU/GNSS sampling, roughness scoring, road matching, uploads, or cloud sync.

Key implementation notes:

- Collector lifecycle states: `IDLE`, `ARMING`, `RECORDING`, `STOP_HOLD` (holds one session open at stops/intersections), `PAUSED_POLICY`, `DEGRADED` (`org.openlgx.roads.collector.lifecycle`)
- Activity recognition uses Google Play services when available; the UI and Diagnostics surface support/permission/update state
- Settings: passive toggle is persisted under DataStore key **`passive_collection_enabled`** (see `AppSettingsRepositoryImpl`)
- Foreground recording uses **location**-type FGS (`CollectorForegroundService` in `AndroidManifest.xml`); notification permission applies on Android 13+
- Debugging: enable **Debug mode** in Settings to reveal manual/simulation controls on Home
- **Cursor/agent NDJSON (debug builds only):** each event is appended to **`Android/data/org.openlgx.roads/files/debug-1ac86d.log`** on the device (UTF-8, one JSON object per line). After reproducing a crash over USB, copy the file to the repo root for analysis, for example:  
  `adb pull /sdcard/Android/data/org.openlgx.roads/files/debug-1ac86d.log debug-1ac86d.log`  
  (On some phones the path is under `/storage/emulated/0/...` — `adb shell ls` that tree if needed.) Logcat also prints **`Debug NDJSON log (adb pull):`** with the absolute path on first launch. Optional HTTP ingest uses cleartext (`src/debug/AndroidManifest.xml`): **emulator** `10.0.2.2:7603`, **physical +** `adb reverse tcp:7603 tcp:7603` for `127.0.0.1:7603`. Release builds are unchanged.

When debugging compile/Hilt/Room issues, check the manifest, `PassiveCollectionCoordinator`, and `README` build commands first.

**Phase 2B1 (fused GNSS capture in passive recording)**

End-to-end passive **RECORDING** now creates a `RecordingSession` (source **`AUTO`** for the normal passive path), starts **fused** location updates under a **foreground service of type `location`** (`CollectorForegroundService`), and persists batched rows to `location_samples` (elapsed realtime, UTC time, lat/lon, speed, bearing, accuracy, altitude when present; eligibility placeholders retained).

- **Arming gate**: Before leaving `ARMING`, the app requires a fused **speed** fix at or above Settings → capture min speed (high-accuracy current location call). Debug “simulate driving” still skips this gate so local testing stays easy. **If the speed gate fails but Activity Recognition still reports IN_VEHICLE**, the coordinator schedules a **10-second retry** that re-triggers the reconcile loop (Play Services only sends IN_VEHICLE ENTER once per transition, so without a retry the coordinator would stay IDLE for the entire drive). The 15-minute `PassiveKeepaliveWorker` also nudges the reconcile loop on each tick as a secondary safety net.
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

- **Activity Transition API** (Play services): the app uses **`requestActivityTransitionUpdates`** (IN_VEHICLE enter/exit, ON_FOOT/STILL enter) delivered to a **manifest-registered** receiver (`ActivityRecognitionUpdatesReceiver`), which **always calls `PassiveCollectionHandle.start()`** before ingesting intents so the coordinator mailbox runs after a **cold process start**.
- **Keepalive**: **`PassiveKeepaliveWorker`** runs on a **15-minute** WorkManager cadence (`passive_keepalive`) and calls `start()` so Activity Recognition is **re-registered** after OEM idle kills. Scheduled from `RoadsApplication.onCreate`, **`BootCompletedReceiver`**, and **`AppUpdatedReceiver`** (`MY_PACKAGE_REPLACED`).
- **Device reboot**: `BootCompletedReceiver` handles `BOOT_COMPLETED` (with `RECEIVE_BOOT_COMPLETED`) so `RoadsApplication` / `PassiveCollectionHandle.start()` runs again and the coordinator can re-subscribe when passive collection is enabled.
- **While recording**: Trip capture still relies on **`CollectorForegroundService`** (location-type FGS); users should see an ongoing notification during recording.
- **Process death before finalize**: If the app process is killed before the coordinator closes the Room row (crash, OOM, aggressive OEM kill), the session can remain **`ACTIVE`** with **`endedAtEpochMs` null** while Home shows **Idle**. On the next reconcile after restart, the coordinator **finalizes that orphan as `COMPLETED`** and schedules processing so backfill and the session list stay consistent.
- **Force stop** (Settings → Apps → *Force stop*): Android **does not** allow the app to restart background components until the user launches the app again. This is platform policy—not something the app can override.
- **OEM battery restrictions**: For best results on aggressive devices, set the app’s battery usage to **Unrestricted** (wording varies by manufacturer). Home and Settings expose **battery optimization exemption** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) when not already exempt.

**Phase 2C (session inspection, local export, validation tooling)**

Browse **Recorded sessions** from Home; each row shows **Hosted:** `hostedPipelineState` (Neon upload queue visibility) alongside processing stats, and opens **Session detail** with id/uuid, timing, source, on-device **processing** columns, sample counts, last GNSS/IMU timestamps, snapshots, and lightweight **validation**. **Settings → Hosted upload diagnostics** includes **Backfill un-uploaded sessions**, which enqueues `SessionUploadWorker` for every **completed** trip whose hosted state is **`NOT_STARTED`**, **`FAILED`**, **`UPLOAD_SKIPPED`**, or **`UPLOADING`** (stale/crashed) (same Wi‑Fi/charging/battery constraints as auto-upload; road-pack gate bypassed for manual backfill so previously-skipped sessions can recover). **Session detail → Upload to hosted** queues one trip (bypasses the “auto after session” toggle and the road-pack gate; still requires hosted upload enabled and valid connection fields). Manual backfill and single-session upload use ExistingWorkPolicy.REPLACE to unstick stale WorkManager entries; auto-upload after session completion still uses ExistingWorkPolicy.KEEP.

**Upload progress (Settings):** Under **Backfill un-uploaded sessions**, while `SessionUploadWorker` is active, a **progress bar** shows **uploaded MB / total MB** for the ZIP transfer to storage (throttled while sending). While **preparing** the export ZIP, WorkManager reports **estimated row progress** (locations + sensors written vs total), so the UI can show a **determinate** bar instead of sitting on “Preparing…” indefinitely on large sessions. **Filtered road-pack uploads** build only the filtered ZIP (no duplicate full export). An **indeterminate** bar still appears when row totals are not yet known or when jobs are **queued** or **blocked** (Wi‑Fi/charging/battery).

**Upload pipeline robustness (dead-end state recovery):** If the worker process is killed between setting UPLOADING and completing the upload (OOM, crash, OS kill), the session previously got stuck in UPLOADING forever with no recovery path. Now: (1) UPLOADING is included in backfill-eligible states so manual backfill can recover it; (2) if upload is disabled while a session is mid-upload, the worker resets the state to NOT_STARTED instead of leaving it orphaned; (3) before re-uploading, the worker checks for an existing ACKED batch (idempotent guard against duplicate uploads after a crash between server-side completion and local batch insert); (4) putHeaders (with Content-Type fallback) is now correctly passed to putToSignedUrl instead of the raw signedUploadHeaders. **Prepared ZIP staging (Room v7):** After a successful export, the worker inserts an `upload_batches` row in **`READY`** with the local ZIP path and **SHA-256**; on **`FAILED_RETRYABLE`** (network/API errors), WorkManager retries **reuse that file** if it still matches the checksum—**no second million-row export** on transient blips. **Large ZIP uploads (hosted alpha):** `SessionUploadWorker` uses **one signed PUT** when the ZIP is ≤ **48 MiB**; larger exports use **multipart** (`uploads-create` / `uploads-complete` with **48 MiB** parts under Edge `CHUNK_MAX_BYTES`, Storage keys `…session.zip.part0000`, …), Neon **`artifacts.part_storage_keys_json`**, and the Python processing worker **concatenates** parts before unzip. **Edge `uploads-create`** allows **1 GiB** logical ZIPs; each part stays under Free tier Storage’s **50 MiB** per object. **Pro+** can raise Storage’s global limit in **Dashboard → Storage** so single-part PUTs can exceed 48 MiB without chunking (see [docs/setup-hosted-alpha.md](docs/setup-hosted-alpha.md)). **redeploy** `uploads-create` and `uploads-complete` after pulling this change; apply Neon migration `0004_multipart_upload_artifacts.sql`. **Open trip review** loads a bundled **WebView** HTML shell (Leaflet + Plotly JS vendored under `app/src/main/assets/review/vendor/`, loaded with `file:///android_asset/review/`). **HTTPS map tiles** are loaded with mixed-content allowed for that `file://` shell, and the map layout calls Leaflet `invalidateSize` after load so the pane is not stuck at zero height (a common cause of a blank/black map). **Home** (on load) and **Recorded sessions** both request **backfill** reprocessing for trips that are `COMPLETED` but **`NOT_STARTED` or `FAILED`** (with at least two GPS fixes), so a failed run can clear without manually hunting through menus. The on-device pipeline must not put **NaN / Infinity** into `org.json.JSONObject` (Android throws *Forbidden numeric value: NaN*); feature JSON now uses `null` for non-finite gyro / heading metrics. Trip-review JSON includes a **`windows`** array (per-window centroid, `roughnessProxy`, `sessionId`); the map **prefers dots from window centroids**, with fallback to per-GPS `roughnessNorm`. **Roughness needs `processingState = COMPLETED`**; the polyline is GNSS-only. **Map UX:** per-trip **checkboxes**, **All trips on map**, and **Only** (one trip); on the WebView toolbar turn **Route** and **Roughness** **on** to see line and dots. **Recorded sessions** embed uses **Full screen map** for an edge-to-edge WebView dialog (close with ✕). Tapping a roughness dot opens a **Leaflet popup** on the map; use **Open trip review →** inside the popup when you want session detail (it no longer jumps away on the first tap). **Session detail → Reprocess** reruns analysis for one trip (same as backfill, but immediate). **Export session** writes a versioned bundle under `<externalFiles>/olgx_exports/` (folder + sibling `.zip`): `session.json`, `manifest.json`, raw `location_samples` / `sensor_samples` as CSV+JSON, plus **derived** `derived_window_features` and **anomaly** `anomaly_candidates` CSV+JSON when present (export schema **v3**+). The manifest adds a **`processing`** object (`processingState`, timestamps, error, summary JSON pointer), derived/anomaly counts, and file role entries for the new artifacts. **Room DB is version 7** with non-destructive migrations from v3 (**`MIGRATION_3_4`**: processing columns, window/geo/heuristic fields, indexes), v4→v5 (**`MIGRATION_4_5`**: `hostedPipelineState` on sessions, extended `upload_batches` for hosted upload queue fields), v5→v6 (**`MIGRATION_5_6`**: `upload_batches.filterChangedPayload`, `uploadSkipReason`), and v6→v7 (**`MIGRATION_6_7`**: `upload_batches.contentChecksumSha256`, `artifactKind` for upload staging). Exported schema: `app/schemas/org.openlgx.roads.data.local.db.RoadsDatabase/7.json`. Session detail surfaces **hosted pipeline state** separately from on-device processing (operator-facing label + upload batch hints).

**Licensing (review assets):** Leaflet (`BSD-2-Clause`) and Plotly (`MIT`) redistributable builds are bundled for offline-first chart/map shells; verify license notices if you replace those files.

Diagnostics shows export root path, last export path/time/success/error, and DB-wide data-quality hints. Home shows a **Status** card: a checklist-style layout with **green checks** when a requirement is satisfied and **red crosses** when something still blocks automatic trips (plus a **scheduled** icon for neutral/in-progress items like “not recording while parked”). Technical details still holds dense diagnostics, with key booleans shown the same way.

**Stop-hold capture policy:** While recording, brief “not driving” flicker no longer ends the trip immediately. After sustained low movement, the collector enters **`STOP_HOLD`** (foreground capture continues). If movement resumes, recording continues the same session. If low movement persists for **`capture_stop_hold_seconds`** (default 180s), the session finalises and optional on-device processing runs. **Reprocess** on session detail rewrites only derived/anomaly rows (raw samples are never deleted by processing).

**Build / test (from repo root)**

| Goal | Command | APK output |
|------|---------|------------|
| Debug APK | `.\gradlew.bat :app:assembleDebug` | `app\build\outputs\apk\debug\app-debug.apk` |
| Signed release APK | `.\gradlew.bat :app:assembleRelease` (requires signing; see below) | `app\build\outputs\apk\release\app-release.apk` |
| Unit tests | `.\gradlew.bat :app:testDebugUnitTest` | — |

**Crash shortly after launch (hosted upload / WorkManager / Room):**
- **WorkManager:** `HiltWorkerFactory` is supplied via `Configuration.Provider` using a Hilt **entry point** (avoids `lateinit` before injection). The manifest removes the default `WorkManagerInitializer`.
- **Room:** Only **v3→v4→v5** migrations exist. Local DB **v1 or v2** is upgraded with **destructive** reset (see `DatabaseModule`); v3+ keeps data.
- If issues persist, capture **logcat** for `AndroidRuntime`, `WorkManager`, `Hilt`, `Room`.

**Prerequisites:** Android SDK; **JDK 17** (Android Studio’s bundled runtime is fine); `local.properties` with `sdk.dir` (Android Studio creates this on first open).

**Pilot bootstrap (Yass Valley alpha field trial):** Debug and **release** APKs set `BuildConfig.PILOT_BOOTSTRAP_ENABLED` and, on launch when DataStore `pilot_bootstrap_content_version` is behind the in-app constant, seed **Neon-aligned** council URL (`…/functions/v1`), slugs, project/device UUIDs (`PilotBootstrapConfig`), and field-trial upload toggles: **cellular allowed**, Wi‑Fi-only **off**, **raw** session ZIP (road filter **off**, avoiding “low value” filtered skips), road-pack **not** required for auto-upload, low-battery upload pause **off**, “charging preferred” delay **off**. **DEVICE_UPLOAD** is **not** `SUPABASE_SECRET_KEY`: mint or rotate a row in Neon with `python backend/scripts/issue_device_upload_key.py`, then keep plaintext in **`local.properties`** / **`ROADS_PILOT_UPLOAD_KEY`** / **`PilotBootstrapConfig.HARDCODED_DEVICE_UPLOAD_API_KEY`** / `backend/.env.local` as `UPLOAD_API_KEY` (alpha testers: rotate/revoke after trial; beware committing plaintext in Kotlin). If every source is empty, bootstrap still fills URLs/UUIDs but leaves **`uploadEnabled = false`**. Bump bootstrap content version in code when defaults change so existing installs pick up new toggles. See Diagnostics / hosted diagnostics for readiness.

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
  sql/migrations/     # Neon Postgres
  supabase/functions/ # Edge (uploads-create, uploads-complete, council-layers-*, healthz)
  publish/, processing/, roadpack-build/  # Python runners

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