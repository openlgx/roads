# Calibration notes (OLGX Roads)

This document explains what **calibration** means for a smartphone roughness collector, what the app **does not** claim, and how Phase F hooks in the Android app relate to literature and agency practice.

## Product stance

- On-device and export scores (present or future) are **experimental proxies** unless explicitly validated against reference data.
- **IRI** (International Roughness Index) is defined from a **longitudinal elevation profile** and a **quarter-car model** (see ASTM E1926—not an accelerometer scale factor by itself).
- Enabling **Phase F — calibration workflow** in Settings only adds **provenance anchors** in `calibration_runs` and export metadata; it is **not** MEMS turntable calibration and **not** profiler certification.

## Two calibration layers

### Layer A — intrinsic sensor (MEMS)

What it is: **bias, scale, misalignment**, sometimes temperature behaviour, for accelerometers and gyroscopes. Consumer phones rely heavily on **factory** calibration; researchers sometimes use **multi-static poses** (gravity as reference) or simple jigs.

Relevance: useful for diagnostics and advanced pipelines; **does not** directly produce IRI.

### Layer B — application / system

What it is: mapping **device-frame** motion (plus speed, vehicle, mount, surface class) to **roughness features** or indices that can be compared to **reference IRI** or council labels.

Relevance: this is where crowd-sourced data can connect to pavement management—**after** structured validation (repeat runs, speed strata, spatial alignment to profiler segments).

## Ground truth chain (agencies)

- [ASTM E1926](https://www.astm.org/e1926-08r21.html) — computing IRI from profile.
- [ASTM E950 / E950M](https://www.astm.org/e0950_e0950m-09.html) — inertial / height-based profiling reference methods.
- **AASHTO R 56** — certification of inertial profiling systems; **AASHTO R 57** — operation and verification (e.g. bounce / correlation checks). Context: [TRB RIP — R 56 update program](https://rip.trb.org/View/1628603). Example operational manual: [Caltrans CTM 387](https://dot.ca.gov/-/media/dot-media/programs/engineering/documents/californiatestmethods-ctm/ctm-387-a11y.pdf).

Certified profilers measure **profile** with controlled QC. Phones measure **vehicle response**—a related but different observable.

## Smartphone – IRI literature (orientation, not guarantees)

Examples used for methodology and field protocols (results are **study-specific**):

- Theoretical / quarter-car style link and DSV validation: [TRID 2001788](https://trid.trb.org/View/2001788), [IJPE (2021)](https://www.tandfonline.com/doi/full/10.1080/10298436.2021.1881783).
- Android field evaluation: [ASCE Library](https://ascelibrary.org/doi/10.1061/JPEODX.0000058).
- ML / classification from IMU features: [Scientific Reports (2025)](https://www.nature.com/articles/s41598-025-34396-3).
- Accelerometer roughness vs profilometry survey: [doi:10.1155/2016/8413146](https://doi.org/10.1155/2016/8413146).
- MEMS error characterisation (navigation-oriented): [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC10490716/), [MDPI Sensors](https://www.mdpi.com/1424-8220/23/17/7609).

## What the app records today (for later calibration)

- Raw IMU streams in **device frame** (see export / session metadata).
- **Device profile** snapshot where available.

## Phase F workflow (app)

With **Settings → Phase F — calibration workflow → Record session-completed calibration anchors** enabled:

- On each **completed** recording session, the app inserts a **`calibration_runs`** row labelled `session_completed_anchor` (JSON parameters include `sessionId`, hook version, pointer to this doc). **Interrupted / policy-aborted** sessions do not create anchors.
- Exports include **`calibrationWorkflowEnabled`** and **`calibrationLiteraturePointer`** in **`manifest.json`** (`exportSchemaVersion` 2+).

When the toggle is off, **`calibration_runs`** is unchanged by session completion (Diagnostics may still show a count from manual or future runs).

## Offline analysis (`analysis/`)

Prefer **speed-stratified** windows, **repeat-run protocols**, and documented **spatial joins** to any reference IRI before interpreting proxies. See [analysis/README.md](../analysis/README.md).
