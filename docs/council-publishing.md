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

The Python entrypoint is `backend/publish/publish_council_layers.py`.

---

## Versioned manifest (`manifest.json`)

Every `manifest.json` MUST be valid JSON with at minimum:

| Field | Type | Description |
|-------|------|-------------|
| `manifestVersion` | int | Bump when shape/semantics change; start at **1** |
| `councilSlug` | string | Owner scope |
| `publishedAt` | string | ISO-8601 UTC |
| `publishRunId` | uuid | Neon `published_layer_runs.id` |
| `layerArtifacts` | object | Map layer name → `{ storageKey, byteSize?, checksum?, mimeType, schemaVersion? }` for roughness, anomalies, consensus (and optional `.fgb` entries) |
| `sourceProcessingVersions` | object | Producer transparency, e.g. `{ "processor": "0.1.0", "exportSchemaVersion": 3 }` — descriptive, not an IRI or certification claim |

The publish job writes the manifest **after** layer files succeed so consumers see a consistent snapshot.

Deterministic key ordering inside `layerArtifacts` is recommended but not required.

---

## Consuming in GIS

- **QGIS / ArcGIS:** Import **GeoJSON** or **FlatGeobuf** from a signed download path exposed by Edge Functions (`council-layers-*`) using a **`COUNCIL_READ`** key.
- Always verify **`manifestVersion`** and **`publishedAt`** before replacing local cached layers.

---

## Related

- [docs/setup-hosted-alpha.md](setup-hosted-alpha.md) — operator setup, secrets
- [docs/api-contract.md](api-contract.md) — Edge URLs and auth
- [ROADMAP.md](../ROADMAP.md) — product phases
