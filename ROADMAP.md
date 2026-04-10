# OLGX Roads Roadmap

## Purpose
This roadmap defines the end-to-end plan for OLGX Roads from on-device collection through cloud ingestion, model training, validation, pilot rollout, and production operations.

It is written to align the Android collector, offline analysis tooling, future backend/database design, and the long-term model/validation pathway.

---

## Current status snapshot

### What is built now
- Android collector foundation is in place.
- Passive trip lifecycle exists, including STOP_HOLD-style trip continuity at intersections / short stops.
- Fused GNSS capture and IMU capture are persisted locally.
- Room DB is at **v5** with on-device **processing** columns plus **hosted pipeline** visibility (`hostedPipelineState`) and extended **`upload_batches`** for the upload queue.
- Session review exists on device, including WebView-based trip review and all-runs review.
- On-device roughness/anomaly processing exists and is explicitly marked experimental.
- Local export bundles include raw and derived artifacts.
- Offline Python analysis tooling remains available and should continue to be the research bench.
- **Hosted alpha foundation (in-repo):** Neon SQL migrations, Supabase Edge Functions (`uploads-create` / `uploads-complete` / `healthz` / `council-layers-*`), Python publish (`publish_council_layers.py`, fail-closed without boundary), processing scaffold, road-pack build script, GitHub Actions workflows, and Android **WorkManager** upload path + **road pack** loading / GeoJSON index / filtered export hooks (see [docs/backend.md](docs/backend.md), [docs/api-contract.md](docs/api-contract.md)).

### What is not yet production-ready
- **Hosted pipeline is alpha:** server-side processing to full hosted roughness is still largely scaffolded; production keys, real LGA boundaries, and field-hardening are required before council-facing claims.
- No model training pipeline is implemented.
- No calibrated IRI model exists.
- No robust road-network map matching exists on device beyond pack-based proximity (no live OSM requirement by design for alpha).
- No fleet/org multi-user workflow exists beyond seeded Neon rows.

---

## Product principles

1. **Raw data is the source of truth.**
   Derived features, anomalies, and scores are reproducible and replaceable.

2. **Experimental means experimental.**
   The current roughness pipeline is a heuristic proxy, not certified IRI.

3. **Validation before claims.**
   We can ship experimental outputs before calibration, but we cannot market them as validated roughness or IRI until supported by evidence.

4. **Local-first Android remains the field edge.**
   The phone app is the collection and first-review tool. Heavy aggregation, training, and publication should move server-side later.

5. **Open-source friendly architecture.**
   The system should stay easy for Cursor and contributors to understand, run, and extend.

---

## Phase roadmap

## Phase 0 - Foundation
**Status:** Complete

Scope:
- Android project scaffold
- Compose + Hilt + Room + DataStore
- initial entities/DAOs/settings
- repo structure
- documentation skeleton

Exit criteria:
- app builds cleanly
- schema is versioned
- project is contributor-friendly

---

## Phase 1 - Collector architecture
**Status:** Complete

Scope:
- session model
- raw sample tables
- policy separation (capture vs upload)
- diagnostics and settings base

Exit criteria:
- collector can persist sessions and samples coherently
- app survives normal lifecycle changes

---

## Phase 2 - On-device capture and review
**Status:** Largely complete, needs hardening

Scope:
- passive lifecycle
- fast arming / stop-hold behavior
- GNSS + IMU collection
- foreground notifications
- session list/detail
- export bundles
- WebView review for per-session and all-runs

Still to harden:
- session fragmentation tuning
- more lifecycle edge-case tests
- review UX polish under larger datasets
- clearer docs for field protocol

Exit criteria:
- one real trip consistently stays one session
- field user can tell with certainty when recording is active
- exported data is trustworthy and complete

---

## Phase 3 - On-device experimental processing
**Status:** Implemented as v1 heuristic layer, not yet validated

Scope:
- Kotlin port of roughness_lab feature extraction ideas
- heuristic labels such as cornering / vertical impact / stable cruise
- derived windows
- anomaly candidates
- session-level proxy scoring
- local consensus bins for all-runs overlay

Important stance:
- this phase is an **experimental heuristic road-response pipeline**
- this is **not** a trained model
- this is **not** IRI

Exit criteria:
- processing runs automatically after completed trips
- outputs are viewable, exportable, and repeatable
- raw data remains untouched by reprocessing

---

## Phase 4 - Validation and labelling
**Status:** Not started operationally

Scope:
- build labelled dataset workflow
- define label taxonomy for:
  - smooth
  - rough
  - pothole / shock
  - corrugation
  - patching / joints
  - cornering / confounded
- establish segment-level ground truth process
- compare repeated traversals
- compare device mounts / vehicles / speeds
- establish validation reports

Deliverables:
- labelling specification
- curated labelled sessions set
- validation notebooks/reports
- metrics dashboard for false positives / false negatives / repeatability

Exit criteria:
- enough labelled data exists to support supervised model work
- confounders are clearly measured

---

## Hosted alpha pipeline foundation
**Status:** Scaffold implemented in repository (operator wiring and pilot data still required)

Scope:
- **Neon** metadata schema (councils, projects, devices, sessions, upload/processing jobs, artifacts, published layer runs, road packs; hashed API keys).
- **Supabase Storage** private buckets and **Edge Functions** for signed upload URLs and council layer reads (`COUNCIL_READ`).
- **Android:** DataStore upload settings, WorkManager + OkHttp upload worker, `hostedPipelineState`, extended upload batch rows, **road pack** directory + GeoJSON `LocalRoadIndex`, `FilteredSessionExporter` (export-only filtering; raw Room rows unchanged).
- **Python:** fail-closed council publish without authoritative boundary; processing runner scaffold; road-pack build + `road_packs` registration.
- **CI:** migration validation, scheduled publish (12h, secrets optional), processing backfill dispatch, optional Supabase CLI deploy.

Documentation:
- [docs/backend.md](docs/backend.md), [docs/setup-hosted-alpha.md](docs/setup-hosted-alpha.md), [docs/api-contract.md](docs/api-contract.md), [docs/council-publishing.md](docs/council-publishing.md), [docs/road-filtering.md](docs/road-filtering.md)

Exit criteria (for moving beyond “foundation”):
- Pilot council has real **LGA boundary** and **road pack** registered.
- **DEVICE_UPLOAD** and **COUNCIL_READ** keys issued and rotated procedure tested.
- End-to-field test: `uploads-create` → signed PUT → `uploads-complete` → processing job visibility.
- Privacy/policy copy for slugs and telemetry reviewed.

**Mapping from on-device state:** `SessionHostedPipelineState` on `RecordingSessionEntity` tracks upload/remote visibility separately from on-device `processingState` where helpful; both may be shown in session detail UI.

---

## Phase 5 - Backend ingestion MVP
**Status:** Partially overlapped by **Hosted alpha foundation** (upload path + metadata + workers); full MVP completion still pending

Scope:
- backend API for upload (**Edge Functions implemented**; expand validation and monitoring)
- authentication strategy (**API keys in Neon implemented**; consider short-lived tokens later)
- upload queue implementation from Android (**WorkManager path implemented**)
- object/file storage for raw exports (**Supabase Storage**)
- ingestion service for normalized database writes (**processing job scaffold**; expand to full parsing and hosted derived features)
- server-side processing job queue (**implemented minimally**; scale and semantics TBD)

### Recommended first backend shape
- API: lightweight service (FastAPI, NestJS, or similar)
- DB: Neon Postgres
- blob storage: S3-compatible bucket or equivalent
- job runner: background worker for parsing, feature extraction, aggregation

### Recommended Neon approach
Start with Neon Free for:
- metadata tables
- session registry
- device registry
- upload manifests
- labels
- segment aggregates

Do **not** put large raw sensor payloads directly into Postgres long term.
Use Postgres for metadata and derived tables; use object storage for large raw bundles.

Exit criteria:
- Android can upload completed session bundles
- backend stores session metadata and processing state reliably

---

## Phase 6 - Cloud data model and ops database
**Status:** Not started

Scope:
- production schema for:
  - organizations
  - fleets / devices
  - recording sessions
  - location and sensor bundle references
  - derived window features
  - anomaly candidates
  - labels
  - segment aggregates
  - model versions
  - validation runs
- migration strategy
- backups
- retention rules

### Database guidance
**Neon is a good fit to start** because:
- free plan is enough for metadata early on
- branching is useful for schema experimentation
- Postgres ecosystem is strong for analytics pipelines
- the open source program is worth applying for if the project stays open and clearly Neon-integrated

See [Neon pricing](https://neon.com/pricing) and the [Neon Open Source Program](https://neon.com/programs/open-source) for current terms.

### Recommended storage split
- **Postgres / Neon:** metadata, labels, derived outputs, aggregates, model versions
- **Object storage:** raw JSON/CSV/ZIP exports and larger training artifacts

Exit criteria:
- schema supports ingestion, curation, and model-development workflows
- raw/derived separation is enforced

---

## Phase 7 - Model training pipeline
**Status:** Not started

Scope:
- convert labelled session data into training datasets
- build reproducible feature pipelines
- benchmark heuristic vs ML models
- try segment-level and window-level modelling
- evaluate model families such as:
  - gradient boosted trees on engineered features
  - temporal CNN / 1D CNN on windowed motion sequences
  - hybrid models combining GPS context + IMU features

### Training principles
- version every dataset
- version every feature set
- version every model
- keep a strong baseline: current heuristics must remain the benchmark
- never train directly against noisy labels without confidence scoring

### First training targets
1. Confounder classifier:
   - cornering vs genuine road-response
2. Anomaly classifier:
   - impact / pothole candidate detection
3. Segment roughness regressor or ordinal classifier:
   - smooth / moderate / rough before any IRI attempt

Exit criteria:
- trained models outperform the heuristic baseline on held-out labelled data
- deployment format is defined for on-device or server-side inference

---

## Phase 8 - Calibration to engineering truth
**Status:** Not started

Scope:
- compare proxy outputs to reference surveys
- compare against council defect logs and known roughness runs
- investigate speed normalization and device normalization
- determine whether an IRI-correlated model is defensible

Important:
- this is the phase where “predicted IRI” may become possible
- it should not be claimed earlier

Exit criteria:
- calibration results are statistically defensible
- model limitations are documented

---

## Phase 9 - Spatial intelligence and segment publishing
**Status:** Not started

Scope:
- road-network matching
- side-of-travel / carriageway logic
- multi-run segment aggregation
- quality-weighted consensus
- GIS-ready outputs for councils

Exit criteria:
- network outputs are stable enough to publish into operational GIS workflows

---

## Phase 10 - Pilot rollout
**Status:** Not started

Scope:
- limited fleet deployment
- support workflow
- monitoring
- upload reliability
- issue triage
- user feedback loops

Exit criteria:
- pilot users can collect, upload, review, and trust outputs
- top operational bugs are known and prioritized

---

## Phase 11 - Production
**Status:** Not started

Scope:
- hardened mobile release
- hardened backend
- access control
- auditability
- data governance
- release process
- support docs
- council onboarding

Exit criteria:
- system is operationally supportable
- claims and limitations are documented clearly

---

## Immediate priorities (next 4-6 weeks)

1. **Harden the Android collector already built**
- tune stop-hold thresholds in field use
- add/expand lifecycle and processing tests
- polish session review for larger trip sets

2. **Freeze the on-device export contract**
- version review payload
- version export schema
- document exact field meanings

3. **Define server ingestion format**
- choose upload bundle contract
- define metadata vs blob split
- define project/device/session identity model

4. **Apply for Neon Open Source Program**
- do this if the repo stays public and Neon becomes the default documented metadata database
- build a minimal `docs/backend.md` showing how Neon fits the architecture

5. **Stand up backend MVP**
- session upload endpoint
- bundle registry
- Neon metadata schema
- object storage for raw bundles

6. **Start structured labelling**
- use exported and uploaded sessions
- create reviewed label sets from real runs

---

## Data architecture moving forward

## On-device
Store:
- raw GNSS samples
- raw sensor samples
- derived windows
- anomalies
- session aggregates
- exports for upload

Do not do on-device long-term:
- full model training
- long-horizon fleet aggregation
- heavy historical analytics

## Backend
Store in Neon:
- orgs, users, devices
- sessions
- upload manifests
- labels
- derived feature metadata
- anomaly metadata
- segment aggregates
- model versions
- calibration runs

Store outside Postgres:
- raw JSON/CSV/ZIP bundles
- training datasets too large for comfortable relational storage
- model artifacts

---

## Roughness detection policy

### Current state
The current roughness detection path is a **ported heuristic pipeline**, not a trained academic-grade roughness model.

That is still valuable because it gives:
- a reproducible baseline
- a way to collect labels
- an on-device screening tool
- a benchmark future models must beat

### Moving forward
The heuristic layer should remain in the product as:
- a fallback baseline
- a feature generator
- a debugging aid
- a regression benchmark against trained models

We should not throw it away once ML begins.

---

## Risks to manage

1. **Heuristic confidence may be mistaken for validation**
   - keep UI copy explicit

2. **Too much raw data in Neon**
   - avoid this from the start

3. **Session fragmentation / lifecycle edge cases**
   - keep tuning and testing on device

4. **Insufficient labels**
   - build the labelling workflow early

5. **Device / vehicle confounding**
   - store device and mount metadata rigorously

6. **Road-network attribution complexity**
   - do not overclaim lane/side-of-road until map matching exists

---

## Decision log

### Database
- Start with **Neon Free** for metadata and development.
- Apply for **Neon Open Source Program** if Neon is integrated as the documented backend and the project remains eligible.
- Keep raw payloads in object storage, not primarily in Postgres.

### Model strategy
- Keep current heuristic roughness pipeline as baseline.
- Move to labelled supervised modelling next.
- Treat IRI as a later calibration outcome, not an immediate deliverable.

### Product strategy
- Android remains the field collector and first-review tool.
- Backend is the aggregation, validation, and publishing layer.

---

## Success definition

OLGX Roads succeeds when it can:
- reliably capture real trips on Android
- upload them cleanly
- review them locally and centrally
- build a labelled dataset from real operations
- train and validate better models over time
- publish useful, trustworthy road-condition intelligence for councils
- do all of the above with an open, contributor-friendly architecture
