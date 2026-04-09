# Roughness confounders — engineering note

This note ties the **README “Initial roughness methodology”** phases (A–F) to **known failure modes** of phone-IMU roughness proxies, and to mitigations we explore **offline** in [`roughness_lab/`](roughness_lab/).

## What the naive proxy conflates

| Phenomenon | Typical signals | Why RMS \(|\mathbf{a}|\) or magnitude windows mislead |
|-------------|-----------------|------------------------------------------------------|
| **Cornering** | Sustained lateral specific force; **heading rate** from GNSS tracks rises with path curvature | Device-frame acceleration mixes gravity component and vehicle dynamics; lateral energy can dominate without pavement roughness |
| **Vertical impact** (pothole, edge) | Short **broadband** spike in accel; high **peak/RMS** | Looks like other shocks unless duration and spatial context are used |
| **Designed discontinuity** (rail crossing, expansion joint, speed table) | Localized, often **repeatable at fixed geo** | Same morphology as defects; needs **geo exclusion** or governance-layer masks |

## Inputs we care about (GNSS + IMU features)

| Input | Role | README phase |
|-------|------|----------------|
| **Raw vs linear acceleration** (sensor types 1 vs 10) | Separates gravity-contaminated vs motion-trending vertical | Phase B — clean/normalize |
| **Per-axis energies** \(x,y,z\) | Distinguish lateral-dominated (turn) vs vertical-dominated motion | Phase D — features |
| **Gyroscope magnitude / yaw rate** | Turn consistency; complements GNSS heading | Phase D |
| **GNSS speed, `bearing` if present** | Speed gating; **heading change rate** as curvature proxy | Phase B/C — quality + windowing |
| **Window duration + peak/RMS** | Shock vs sustained maneuver | Phase C–D |

**Trust decision (sketch, not policy):**

| Situation | Roughness proxy |
|-----------|-----------------|
| Low speed, GPS poor | Down-weight or flag |
| High **heading rate variance** + lateral energy dominance | Prefer **cornering** interpretation; do **not** attribute spike to pavement alone |
| Isolated **peak/RMS** spike, short duration, moderate heading rate | Candidate **vertical impact** (still not IRI) |
| Known excluded geometry (future masks) | **Mask** before aggregation to segments/tiles |

## Exclusion layer (product / governance)

Council-maintained **GeoJSON** geometries (see [`specs/geo_exclusion_mask.schema.json`](../specs/geo_exclusion_mask.schema.json)) are **not** part of the roughness estimator itself:

\[
\text{display} = g(\text{score}, \text{exclusions}, \text{policy})
\]

Audit fields (`source`, `validFrom` / `validTo`, `id`) matter as much as the polygon.

## Where this is implemented

- **Offline prototypes:** [`roughness_lab/run.py`](roughness_lab/run.py) — window features + turn vs shock heuristics (experimental).
- **Labels + metrics:** [`roughness_lab/eval_labels.py`](roughness_lab/eval_labels.py), [`roughness_lab/LABEL_FORMAT.md`](roughness_lab/LABEL_FORMAT.md).
- **Exploratory plots:** existing [`analyze_sessions.py`](analyze_sessions.py).

All outputs remain **experimental** — not IRI, not calibration-validated.
