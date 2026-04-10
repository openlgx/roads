# Pilot readiness — one council, one device (hosted alpha)

Single-council field pilot: **Android** (local-first) + **Neon** (metadata/jobs) + **Supabase** (Storage + Edge Functions) + **GitHub Actions** (processing/publish). Not multi-council production.

**Canonical operator path (Windows PowerShell, repo root):** run the numbered steps in [Tomorrow — one straight line](#tomorrow--one-straight-line-powershell) after prerequisites.

---

## What is ready for pilot

- **Android:** Room v6 hosted pipeline + WorkManager upload; road-pack gate for auto-upload; Settings diagnostics (no secrets); session detail hosted hints.
- **Backend:** `uploads-create` / `uploads-complete` (storage verified on complete); council layer functions; `publish_council_layers.py` **fail-closed** without LGA boundary; outputs remain LGA-clipped / council-scoped by design.
- **Scripts:** `apply_neon_migrations.py` (ordered `backend/sql/migrations`, tracks applied files), `seed_pilot_council.py`, `issue_device_upload_key.py` (new **DEVICE_UPLOAD** when seed plaintext was lost), `pilot_preflight.py`, `pilot_smoke_e2e.py`.

---

## What stays experimental

- Heuristic roughness/anomaly outputs (not IRI).
- Consensus may be empty under density gates.
- Layers refresh on publish schedule, not in real time after every drive.

---

## Prerequisites (once)

| Item | Notes |
|------|--------|
| `backend/.env.local` | From `backend/.env.example`; **never commit**. Scripts load it via `python-dotenv`. |
| Python | `pip install python-dotenv httpx psycopg[binary]` |
| Supabase CLI | For `supabase link` / `supabase functions deploy` from `backend\supabase` |
| Neon branch | `DATABASE_URL` or `DATABASE_URL_POOLED` must point at the branch you migrate + seed |

Edge Function secrets (Dashboard → Edge Functions → Secrets, or `supabase secrets set`): Neon `DATABASE_URL` / `DATABASE_URL_POOLED`; service URL + service role are usually auto-injected as `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY`. Bucket names: if the CLI rejects `SUPABASE_*` names, set **`RAW_BUCKET`** and **`PUBLISHED_BUCKET`** to the same values as in `backend/.env.local`. See [setup-hosted-alpha.md](setup-hosted-alpha.md). Do **not** paste these into the Android app.

---

## Tomorrow — one straight line (PowerShell)

From repository root (`c:\cursor-dev\roads` or your clone). Replace `YOUR_SLUG` and paths with your pilot values. **Do not** paste real keys into scripts committed to git; use env vars or the seed script output.

### 1–3 — Database + seed + deploy

```powershell
# 1) Apply Neon migrations (reports applied vs already applied)
python backend\scripts\apply_neon_migrations.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# 2) Seed one council (boundary GeoJSON + canonical road-pack key + keys) — example:
# python backend\scripts\seed_pilot_council.py `
#   --council-slug YOUR_SLUG `
#   --council-name "Your Council Name" `
#   --project-slug alpha `
#   --boundary-geojson .\path\to\lga.geojson `
#   --road-pack-version 1.0.0 `
#   --road-pack-storage-key roadpacks/YOUR_SLUG/1.0.0/public-roads.geojson `
#   --stable-install-id your-stable-device-id

# 3) Deploy all Edge Functions (config.toml lives here)
Set-Location backend\supabase
supabase link --project-ref $env:SUPABASE_PROJECT_REF
supabase functions deploy --project-ref $env:SUPABASE_PROJECT_REF
Set-Location ..\..
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
```

If `SUPABASE_PROJECT_REF` is not in the environment, pass `--project-ref <ref>` explicitly (same ref as the Supabase project).

### 4–5 — Preflight + smoke

If you already have council/project/device rows but **lost the DEVICE_UPLOAD plaintext**, mint one (writes a new `api_keys` row; revokes prior active DEVICE_UPLOAD keys for that device):

```powershell
python backend\scripts\issue_device_upload_key.py
# Add printed key to backend\.env.local as UPLOAD_API_KEY and PILOT_DEVICE_UPLOAD_KEY
```

```powershell
python backend\scripts\pilot_preflight.py --council-slug YOUR_SLUG
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Paste plaintext keys from seed output (or your password manager), then:
$env:PILOT_DEVICE_UPLOAD_KEY = "olgx_du_..."   # DEVICE_UPLOAD from seed
$env:PILOT_COUNCIL_READ_KEY = "olgx_cr_..."    # COUNCIL_READ from seed

python backend\scripts\pilot_smoke_e2e.py --council-slug YOUR_SLUG
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# After first successful publish (step 10), prove manifest + layers 200:
python backend\scripts\pilot_smoke_e2e.py --council-slug YOUR_SLUG --require-published
```

**Optional smoke modes**

- DB-less preflight (env only): `python backend\scripts\pilot_preflight.py --skip-db`
- No Storage PUT: `python backend\scripts\pilot_smoke_e2e.py --council-slug YOUR_SLUG --skip-upload-e2e`
- Without device key, upload stages show **SKIP** (not FAIL).

### 6–7 — Android + road pack + one session

1. Install APK; open **Settings → Hosted alpha upload**.
2. **Save hosted connection:** upload base URL (`https://<ref>.supabase.co/functions/v1`), DEVICE_UPLOAD key, council slug, project slug, **project id** and **device id** from seed output. Leaving the API key field blank keeps the previously saved key.
3. Sideload road pack: `road_packs/<council_slug>/<version>/public-roads.geojson` under the app files dir (see [road-filtering.md](road-filtering.md)).
4. Enable **Hosted upload** when ready; **Require road pack for auto-upload** remains on in recommended defaults — missing pack **fails closed** with a clear diagnostics error (not silent).
5. Record one drive; confirm session **hosted** state and Settings **Last upload** lines.

### 8–10 — Confirm upload, processing, publish

1. **Neon / Storage:** operator confirms `upload_jobs` completed and object in raw bucket (see `docs/backend.md`).
2. **Processing:** from repo root, `python backend\processing\run_processing_job.py` (loads `backend/.env.local`; repo root is on `sys.path`), or wait for GitHub Actions worker.
3. **Publish:** `python backend\publish\publish_council_layers.py` from repo root (same `.env.local`). Without LGA geometry, publisher **fail-closed** (stderr message; no published writes for that council).

### 11 — Manifest / GIS

`GET {SUPABASE_PROJECT_URL}/functions/v1/council-layers-manifest?councilSlug=YOUR_SLUG` with header `Authorization: Bearer <COUNCIL_READ>`.

Layer paths: `council-layers-roughness`, `council-layers-anomalies`, `council-layers-consensus` — same auth. Contract: [api-contract.md](api-contract.md).

---

## Pass / fail signals

| Step | Pass | Fail |
|------|------|------|
| `apply_neon_migrations.py` | Exit **0**; summary `N applied, M already applied` | Exit **1**; connection or SQL error |
| `pilot_preflight.py` | `SUMMARY: PASS` / `PILOT PREFLIGHT OK` | `SUMMARY: FAIL` bullet list; exit **1** |
| `pilot_smoke_e2e.py` | `RESULT: OK`; stages 1–5 **PASS** lines | Any **FAIL** in summary; exit **1** |

**Smoke stages (see script headings):**

1. **healthz** — 200 (Neon up) or 503 (Edge up, Neon down).
2. **Invalid manifest key** — 401, or SKIP when DB secrets missing on Edge.
3. **Valid COUNCIL_READ** — manifest 200 or 404 (404 OK before first publish unless `--require-published`).
4. **Layer endpoints** — 200 or 404 before publish.
5. **uploads-create** → Storage PUT → **uploads-complete** — full E2E unless skipped.

---

## Seed script (contract)

**Order:** migrations → ** seed** → **build_road_pack** (optional checksum refresh) → preflight → device.

Storage key shape (must match `--council-slug` and `--road-pack-version`):

`roadpacks/<council-slug>/<road-pack-version>/public-roads.geojson`

```powershell
pip install psycopg[binary] python-dotenv
python backend\scripts\seed_pilot_council.py `
  --council-slug bayside-vic-example `
  --council-name "Bayside City Council (example)" `
  --project-slug alpha `
  --boundary-geojson .\pilot_boundary.geojson `
  --road-pack-version 1.0.0 `
  --road-pack-storage-key roadpacks/bayside-vic-example/1.0.0/public-roads.geojson `
  --stable-install-id pixel-8-field-vehicle-01
```

Use your real slugs, boundary file, and **stable_install_id** matching the device row.

---

## Android build

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

---

## GIS consumption (QGIS / ArcGIS Pro)

- **Manifest** and **layer** URLs require **COUNCIL_READ** (Bearer or `X-Api-Key` per function).
- Use `publishedAt` / `publishRunId` from manifest for freshness.
- QGIS: add HTTP(S) vector / protocol URL with headers as your QGIS version allows.

Details unchanged from prior revision: manifest fields and refresh cadence notes remain normative in [api-contract.md](api-contract.md) and [council-publishing.md](council-publishing.md).

---

## Files to keep out of git

`backend/.env.local`, keystores, plaintext keys — see [.gitignore](../.gitignore). Rotate anything exposed.

---

## Related

- [setup-hosted-alpha.md](setup-hosted-alpha.md) — stack + Edge secrets
- [docs/backend.md](backend.md)
- [docs/api-contract.md](api-contract.md)
