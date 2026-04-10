# Council layer publishing

This document describes how **published council layers** are produced, versioned, and consumed. It complements the **normative manifest** fields in [api-contract.md](api-contract.md) storage section.

---

## Cadence

- **GitHub Actions** workflow `publish-council-layers.yml` runs on a **`0 */12 * * *`** schedule (every 12 hours) and supports **manual `workflow_dispatch`**.
- When CI secrets are absent, the workflow is documented to **exit successfully** (no-op) so forks and early clones do not fail CI.

---

## Fail-closed boundary rule

Publishing **must not** emit full-extent or unclipped fallbacks when an authoritative LGA boundary is missing or invalid.

For each council eligible to publish:

1. Load the **authoritative** `lga_boundaries` row for `council_id`.
2. If there is **no geometry**, or geometry is empty/invalid: **skip** that council with a clear error in logs (and optional `published_layer_runs` row with `FAILED` + reason). **Do not** write `published/{councilSlug}/*` artifacts or update `manifest.json` for that council beyond what failure metadata requires.
3. If boundary is present: select eligible hosted data **scoped by `council_id`**, **clip** every feature to the boundary (`ST_Intersection` with PostGIS or equivalent in Python), drop empty geometries, then write outputs.

**Council isolation:** Neighbouring LGAs must not appear. Features that cross the boundary are **clipped**, not included whole.

---

## Output artifacts

Under **private** bucket `roads-alpha-published` (name configurable via env):

- `published/{councilSlug}/roughness/latest.geojson`
- `published/{councilSlug}/anomalies/latest.geojson`
- `published/{councilSlug}/consensus/latest.geojson`
- Optional `.fgb` siblings when the pipeline emits FlatGeobuf.
- `published/{councilSlug}/manifest.json` — **versioned** manifest (see below).

The Python entrypoint is `backend/publish/publish_council_layers.py`. Hosted rows are joined with **`council_id` + `project_id`**, geometries are **Shapely-clipped** to the authoritative LGA polygon, and outputs are **sorted** for deterministic GeoJSON.

**Consensus:** A council-wide line trace is emitted only when **at least 2** distinct `recording_session_id` values contribute **≥ 50** in-bound window points; otherwise an **empty** `consensus` FeatureCollection is still uploaded so Edge URLs remain stable. `manifest.consensusEmitted` records the outcome.

---

## Versioned manifest (`manifest.json`)

Every `manifest.json` MUST be valid JSON with at minimum:

| Field | Type | Description |
|-------|------|-------------|
| `manifestVersion` | int | Bump when shape/semantics change; current publish writes **2** |
| `councilSlug` | string | Owner scope |
| `publishedAt` | string | ISO-8601 UTC |
| `publishRunId` | uuid | Neon `published_layer_runs.id` |
| `layerArtifacts` | object | Map layer name → `{ storageKey, mimeType, schemaVersion, byteSize, omitted? }` plus optional notes |
| `consensusEmitted` | bool | Whether multi-session density gate passed |
| `sourceProcessingVersions` | object | Producer transparency — descriptive, not an IRI or certification claim |
| `refreshCadenceNote` | string | Explains scheduled publish cadence for operators |
| `disclaimer` | string | Non-IRI / experimental statement |

The publish job writes the manifest **after** layer files succeed so consumers see a consistent snapshot.

Deterministic key ordering inside `layerArtifacts` is recommended but not required.

---

## Consuming in GIS

- **QGIS / ArcGIS:** Import **GeoJSON** (primary for alpha) from Edge Function URLs (`council-layers-*`) using a **`COUNCIL_READ`** key. **FlatGeobuf** for published layers is **not** emitted in the current pilot slice.
- Always verify **`manifestVersion`**, **`publishedAt`**, and **`publishRunId`** before replacing local cached layers.

**Pilot URLs, key handling, and refresh expectations:** [pilot-readiness.md](pilot-readiness.md) (GIS validation section).

### After a successful publish

From the same machine that has `backend/.env.local`, run `python backend/scripts/pilot_smoke_e2e.py --council-slug <slug> --require-published` with **`PILOT_COUNCIL_READ_KEY`** (and upload key env vars if you want the full upload path in the same run). **Pass** means manifest and layer checks report HTTP 200 (see script summary block). **Fail** is non-zero exit with `FAIL …` lines (missing manifest or GeoJSON objects in Storage surface as HTTP 404 from the Edge functions).

---

## Related

- [docs/pilot-readiness.md](pilot-readiness.md) — single-council pilot operator pack
- [docs/setup-hosted-alpha.md](setup-hosted-alpha.md) — operator setup, secrets
- [docs/api-contract.md](api-contract.md) — Edge URLs and auth
- [ROADMAP.md](../ROADMAP.md) — product phases
