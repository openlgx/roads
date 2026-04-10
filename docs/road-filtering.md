# Road filtering and road packs (alpha)

The hosted alpha pipeline supports **optional** upload-time filtering so bundles sent to the cloud can emphasise public-road context **without changing** local raw truth in Room (`recording_sessions`, `location_samples`, `sensor_samples` remain intact).

---

## Principles

1. **Local raw data is authoritative** on device. Filtering applies only to **derived export copies** (ZIP / manifest sidecars) used for upload.
2. Every filtered bundle MUST include **`sessionUuid`** matching `RecordingSessionEntity.uuid` and server `client_session_uuid`.
3. **No live OSM from phones** for alpha: road geometry comes from an **imported road pack** (operator-prepared, license-compliant), not from on-device network calls to OpenStreetMap.

---

## Road pack format (alpha)

- Packs are stored under app-private storage, e.g. `filesDir/road_packs/{councilSlug}/{version}/`.
- **Alpha implementation** reads **GeoJSON** `FeatureCollection` with **LineString** or **MultiLineString** geometries (`LocalRoadIndex`).
- **FlatGeobuf** (`.fgb`) is the documented interchange for heavier packs and CI (`roadpack-build`); optional in-app reader may land later.
- Pack production: `backend/roadpack-build/build_road_pack.py` — clip public-road source to the **same LGA boundary** as the council, version, checksum, upload to storage, register **`road_packs`** in Neon.

---

## Classification

Along the GNSS path the evaluator samples at roughly **~25 m** or **~5 s** intervals (whichever is stricter in configuration), measures distance to the nearest road polyline, and maps to `RoadEligibilityDisposition`:

- **LIKELY_PUBLIC_ROAD** — on or aligned with pack geometry
- **NEAR_PUBLIC_ROAD** — close but not fully aligned (proximity band)
- **UNLIKELY_OR_OFF_ROAD** / **PRIVATE_OR_CARPARK_HEURISTIC** — suppressed or trimmed depending on policy

User-facing copy may use shortened labels (e.g. ON / NEAR / OFF); the persisted enum is the source of truth.

---

## Unknown / ambiguous policy

`UploadRoadFilterUnknownPolicy` (DataStore / settings):

- **UPLOAD** — include ambiguous segments in the filtered export
- **SUPPRESS** — drop prolonged low-confidence segments from the **export copy** only
- **TRIM** — shorten or clip segments at boundaries (export copy only)

Diagnostics and Settings should show **active pack version**, **council slug**, and **last load error**. If **`uploadRoadPackRequiredForAutoUpload`** is true and no pack is present, **auto-upload** after session must not run.

---

## Filtered bundle contents

- Wraps or reuses `SessionExporter` output; adds **`roadFilterSummaryJson`** (and/or manifest fields) describing classification summary.
- `FilteredSessionExporter` in the app builds the artifact used by `SessionUploadWorker` when filtering is enabled.

---

## Related

- [docs/api-contract.md](api-contract.md) — `FILTERED_UPLOAD` and `artifactKind`
- [docs/setup-hosted-alpha.md](setup-hosted-alpha.md) — seeding road packs and boundaries
- [README.md](../README.md) — export schema and local-first behaviour
