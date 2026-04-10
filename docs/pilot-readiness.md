# Pilot readiness — one council, one device (hosted alpha)

This pack describes how to run **one** real council pilot with the current stack: Android local-first collector, Neon metadata/jobs, Supabase Storage + Edge Functions, GitHub Actions for processing/publish.

It is **not** multi-council production, calibrated IRI, or fleet scale-out.

---

## What is ready for pilot

- **Android:** Room v6 (hosted pipeline + upload skip metadata); WorkManager upload with road-pack filtering; Settings diagnostics (no secrets); session detail operator state for hosted path.
- **Backend:** `uploads-create` / `uploads-complete` (complete verifies object exists in Storage and matches declared byte size); council layer Edge functions; publish script LGA-clipped and **fail-closed** without boundary.
- **Tooling:** `backend/scripts/pilot_preflight.py`, `pilot_smoke_e2e.py`, `seed_pilot_council.py`; `backend/.env.example` for operator setup.

## What stays experimental / not production-ready

- Roughness / anomaly outputs are **heuristic**, not IRI or certified condition scores.
- Consensus layers use a **density gate**; sparse pilots may see empty consensus while roughness/anomalies still emit.
- Edge cold starts, Storage latency, and GitHub schedule jitter mean council layers are **eventually consistent**, not real-time.
- **FlatGeobuf** for published layers is **not** emitted in this slice; use GeoJSON URLs from the manifest.

---

## Operator checklist (before testers install the APK)

1. **Neon:** Apply SQL migrations under `backend/sql/migrations/` to the target branch.
2. **Seed pilot rows:** Run `backend/scripts/seed_pilot_council.py` with a real LGA GeoJSON boundary (WGS84 Polygon/MultiPolygon). Store printed API keys in a password manager. **Rotate** any key ever pasted into chat, screenshots, or committed files.
3. **Supabase:** Private buckets for raw + published; Edge Functions deployed with the same env names as `backend/.env.example`.
4. **Road pack:** Build or import GeoJSON; register `storage_key` in `road_packs`; place a copy on device at `files/road_packs/<council_slug>/<version>/public-roads.geojson` (ADB `run-as` or device file copy). See [road-filtering.md](road-filtering.md).
5. **Android:** Set `uploadBaseUrl` (e.g. `https://<ref>.supabase.co/functions/v1`), `uploadApiKey` (DEVICE_UPLOAD plaintext), `uploadProjectId`, `uploadDeviceId`, `uploadCouncilSlug` via your secure provisioning path (not in source control). Use **Apply recommended pilot upload defaults** in Settings as a starting point, then enable **Hosted upload** when ready.
6. **Verify:** `python backend/scripts/pilot_preflight.py` (with DB URL) and `python backend/scripts/pilot_smoke_e2e.py --council-slug <slug>`.
7. **Secret hygiene:** **Never** put `DATABASE_URL`, `SUPABASE_SECRET_KEY`, or Neon credentials in the app. Testers only get **DEVICE_UPLOAD** + functions base URL + UUIDs.

---

## Preflight and smoke commands

From repo root (install Python deps as needed: `pip install python-dotenv httpx psycopg[binary]`).

```bash
# Env checks + DB seed/boundary/key rows (set DATABASE_URL in backend/.env.local)
python backend/scripts/pilot_preflight.py --council-slug YOUR_SLUG

# Skip DB if you only want env/file sanity
python backend/scripts/pilot_preflight.py --skip-db

# Edge + manifest contract (set PILOT_DEVICE_UPLOAD_KEY / PILOT_COUNCIL_READ_KEY or flags)
python backend/scripts/pilot_smoke_e2e.py --council-slug YOUR_SLUG \
  --device-upload-key "$PILOT_DEVICE_UPLOAD_KEY" \
  --council-read-key "$PILOT_COUNCIL_READ_KEY"
```

---

## One-session end-to-end (operator / developer)

1. **Device:** Complete a drive; confirm session shows hosted state progressing (or a clear failure reason in Settings diagnostics).
2. **Neon:** Confirm `recording_sessions`, `upload_jobs` COMPLETED, `artifacts` row, `processing_jobs` lifecycle.
3. **Processing:** Run `python backend/processing/run_processing_job.py` with env from `backend/.env.local` (or wait for GitHub Actions worker).
4. **Publish:** Run `python backend/publish/publish_council_layers.py` (or scheduled workflow).
5. **GIS:** Fetch manifest via `council-layers-manifest?councilSlug=…` with **COUNCIL_READ** Bearer key; confirm `publishedAt`, `publishRunId`, `layerArtifacts`, `consensusEmitted`, `refreshCadenceNote`.

---

## GIS consumption (QGIS / ArcGIS Pro)

### Stable contract

- **Manifest:** `GET {SUPABASE_PROJECT_URL}/functions/v1/council-layers-manifest?councilSlug=<slug>`  
  Header: `Authorization: Bearer <COUNCIL_READ plaintext key>` (or `X-Api-Key` per function CORS/auth docs).
- **Layers (GeoJSON):** URLs are **not** anonymous; use the same read-only key as for the manifest. Layer function paths match `docs/api-contract.md` (`council-layers-roughness`, `council-layers-anomalies`, `council-layers-consensus`).
- **Manifest JSON fields:** `manifestVersion`, `councilSlug`, `publishedAt`, `publishRunId`, `layerArtifacts` (per-layer `storageKey`, `byteSize`, `mimeType`, `schemaVersion`; consensus may include `omitted` + `note`), `consensusEmitted`, `disclaimer`, `refreshCadenceNote`, `sourceProcessingVersions`.

### QGIS

1. Add a **Vector Layer → HTTP/S** or use **Protocol** URL (depending on QGIS version) pointing at the **manifest** or **GeoJSON layer** URL with the key passed per server configuration or as documented in your Supabase/edge setup.
2. Set refresh **manually** or on a timer; automated publish may run on a **~12 hour** schedule in alpha — “updates every 12 hours” means the **scheduled GitHub job** is the usual refresh driver, not the moment a car finishes a trip.

### ArcGIS Pro

1. Use **Add Data → From Path** / URL (or AGOL hosted layer workflow your council allows) with the HTTPS GeoJSON URL and HTTP header for the read key where supported; some setups require a small gateway — document the council’s IT constraint honestly.

### Freshness

- Use **`publishedAt`** and **`publishRunId`** in the manifest to confirm which publish produced the current tiles. If `publishedAt` is older than your last successful processing run, publish may not have run yet or failed (check `published_layer_runs` in Neon).

---

## Seed script (one council)

```bash
cd backend
pip install psycopg[binary] python-dotenv
# Prepare pilot_boundary.geojson (authoritative LGA geometry)
python scripts/seed_pilot_council.py \
  --council-slug olgx-pilot \
  --council-name "OLGX Pilot" \
  --project-slug alpha \
  --boundary-geojson ./pilot_boundary.geojson \
  --road-pack-storage-key roadpacks/olgx-pilot/1.0.0/public-roads.geojson \
  --stable-install-id pilot-device-001
```

Align `stable_install_id` with the app’s install/profile story when you issue the **DEVICE_UPLOAD** key scoped to the seeded device row.

---

## Android build / schema

- Compile: `./gradlew :app:compileDebugKotlin` (ensure `JAVA_HOME` is set on CI/CLI).
- Room **v6** schema export: `app/schemas/org.openlgx.roads.data.local.db.RoadsDatabase/6.json` (regenerate via KSP when entities/migrations change).

---

## Safeguards in this slice

- **`uploads-complete`:** After metadata match, verifies the Storage object exists and **byte size** matches the upload job (ranged GET + `Content-Range` total).
- **Auth:** Existing API-key hashing and project/device/council scoping on Edge functions remain the primary controls; there is **no** distributed rate limiter in this slice — size limits and scoped keys reduce abuse; document remaining limits honestly for councils.

---

## Files to keep out of git

- `backend/.env.local`, any root `.env.local`, keystores, plaintext keys.
- Follow [.gitignore](../.gitignore). **Rotate** credentials if they were ever committed or screenshotted.

---

## Exact next step for first end-to-end pilot run

1. Seed Neon with **real** boundary + keys (`seed_pilot_council.py`).
2. Deploy Edge functions + confirm buckets.
3. Run `pilot_preflight.py` (no `--skip-db`).
4. Configure one Android device + sideload road pack + enable upload in Settings.
5. One drive → verify upload → run processing → run publish → open manifest in QGIS/ArcGIS as above.
