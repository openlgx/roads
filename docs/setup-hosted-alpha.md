# Setup: hosted alpha (Neon + Supabase + CI)

This guide is for **operators and contributors** wiring the ingestion/publish stack. The Android app **never** receives Neon database credentials; it only needs **`uploadBaseUrl`** (Supabase functions base) and a **`DEVICE_UPLOAD`** API key (see security notes).

**Single-day pilot:** follow the numbered PowerShell path in [pilot-readiness.md](pilot-readiness.md) (`apply_neon_migrations` → `seed_pilot_council` → deploy → `pilot_preflight` → `pilot_smoke_e2e` → device → processing → publish → manifest).

---

## Architecture summary

- **Neon Postgres:** OLGX metadata (`councils`, `projects`, `recording_sessions`, `upload_jobs`, `processing_jobs`, `artifacts`, `api_keys`, `road_packs`, published layer runs, etc.). Edge Functions use **`DATABASE_URL`** or **`DATABASE_URL_POOLED`**.
- **Supabase Storage:** Private buckets (e.g. `roads-alpha-raw`, `roads-alpha-published`) for ZIPs and published GeoJSON/manifests.
- **Supabase Edge Functions:** Deno/TypeScript under `backend/supabase/functions/`.
- **GitHub Actions:** Migration check, scheduled publish, optional processing backfill, optional functions deploy.

`SUPABASE_DB_*` in `.env.example` is **documented for completeness**; **OLGX app schema is not dual-written to Supabase Postgres** in this release—single source of truth is Neon.

---

## Repository layout

| Path | Purpose |
|------|---------|
| `backend/sql/migrations/` | Ordered SQL for Neon |
| `backend/scripts/` | `validate-migrations.ps1`, `migrate-neon.sh` (if present) |
| `backend/supabase/` | `config.toml`, `functions/*` |
| `backend/supabase/functions/_shared/` | TS helpers (CORS, JSON, Neon pool, auth, paths) |
| `backend/publish/` | `publish_council_layers.py` |
| `backend/processing/` | `run_processing_job.py` (+ `libs/hosted_analysis.py`, `PYTHONPATH` = repo root in CI) |
| `backend/roadpack-build/` | `build_road_pack.py` |

---

## Environment variables

See **`backend/.env.example`** (repo template; copy to gitignored `backend/.env.local`). Typical names:

| Variable | Used by |
|----------|---------|
| `DATABASE_URL` | Migrations, long workers, direct SQL |
| `DATABASE_URL_POOLED` | Edge Functions (serverless-friendly) |
| `SUPABASE_PROJECT_URL` | Functions (Storage API base) |
| `SUPABASE_PROJECT_REF` | CLI deploy workflows |
| `SUPABASE_SECRET_KEY` | Functions (service role; Storage signed URLs) |
| `SUPABASE_RAW_BUCKET` | Raw + upload prefix bucket |
| `SUPABASE_PUBLISHED_BUCKET` | Published layers |
| `SUPABASE_ACCESS_TOKEN` | GitHub Action for CLI deploy (optional) |

**Android (DataStore):** `uploadBaseUrl`, `uploadApiKey`, and upload policy fields—see `AppSettings` and Settings UI.

---

## Neon branches

- Use **separate** Neon branches for **dev** and **prod** (or `main` + preview).
- Apply the same ordered migration chain to each branch; `DATABASE_URL` / `_POOLED` point at the correct branch connection string.

**PostGIS:** Migrations enable `postgis` for `lga_boundaries.geometry`. If your plan disallows it temporarily, fall back to boundary stored as GeoJSON in JSONB (documented in migrations/README if you deviate).

---

## Supabase buckets

Create private buckets matching env names (e.g. `roads-alpha-raw`, `roads-alpha-published`). Configure **RLS/policies** so **only service role** used by Edge Functions can create signed URLs; clients never use anon key for raw upload.

---

## API keys (`api_keys` table)

Key types (Postgres check):

- **`DEVICE_UPLOAD`** — Android: `uploads-create`, `uploads-complete` only. Scope rows with `project_id` and optional `device_id`.
- **`COUNCIL_READ`** — Council manifest + layer GET functions; scope to `council_id` or equivalent.
- **`INTERNAL_ADMIN`** — Reserved for operators/automation; GitHub Actions should prefer **service credentials** (`SUPABASE_SECRET_KEY`, Neon URL) rather than app keys.

**Issuance:** Insert row with **SHA-256 hash** of the secret (see `backend/supabase/functions/_shared/auth.ts`). Store plaintext secret only in a password manager; give the device the raw key once.

---

## GitHub Actions secrets

GitHub does **not** allow the `secrets` context in **job-level** `if:` (you will get *Unrecognized named-value: 'secrets'*). Workflows here always run the job and **skip inside the step** with `exit 0` when required secrets are empty.

Typical secrets:

- `DATABASE_URL` — publish/processing jobs
- `SUPABASE_SECRET_KEY`, `SUPABASE_PROJECT_URL` — Storage from Python
- `SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF` — optional CLI deploy

**Safe no-op:** When secrets are missing, scheduled jobs should exit **0** so public CI does not fail.

---

## Migrations locally

From repo root (adapt for your shell):

```powershell
# Applies 0001 → 0003 in order using backend/.env.local (python-dotenv)
python backend/scripts/apply_neon_migrations.py
```

Alternatively, run each file in order with `psql` against Neon.

Or use `backend/scripts/validate-migrations.ps1` for syntax smoke (see workflow `migration-check.yml`).

---

## Edge Functions deploy

**CLI project root** is `backend/supabase` (where `config.toml` lives). This matches GitHub Actions job `working-directory: backend/supabase` in `.github/workflows/supabase-functions-deploy.yml`, which runs `supabase functions deploy --project-ref …` to push **all** function folders under `backend/supabase/functions/` (for example `uploads-create`, `uploads-complete`, `healthz`, `council-layers-manifest`, `council-layers-roughness`, `council-layers-anomalies`, `council-layers-consensus`).

### Bash (repo root)

```bash
cd backend/supabase
supabase link --project-ref <ref>
supabase functions deploy --project-ref <ref>
```

### PowerShell (repo root)

```powershell
Set-Location backend\supabase
supabase link --project-ref <ref>
supabase functions deploy --project-ref <ref>
```

### Per-function deploy (optional)

If you prefer explicit names (same directory as above):

```bash
cd backend/supabase
supabase functions deploy uploads-create --project-ref <ref>
supabase functions deploy uploads-complete --project-ref <ref>
supabase functions deploy healthz --project-ref <ref>
supabase functions deploy council-layers-manifest --project-ref <ref>
supabase functions deploy council-layers-roughness --project-ref <ref>
supabase functions deploy council-layers-anomalies --project-ref <ref>
supabase functions deploy council-layers-consensus --project-ref <ref>
```

Configure **every** deployed function’s secrets in the Supabase dashboard or CLI. **Minimum Neon:** `DATABASE_URL` or `DATABASE_URL_POOLED` (without this, `healthz` returns **503**). **Storage / Supabase JS client:** the CLI **refuses** custom secret names that start with `SUPABASE_` (except platform-managed keys). Edge code therefore resolves, in order: `SUPABASE_SECRET_KEY` **or** auto-injected `SUPABASE_SERVICE_ROLE_KEY`; `SUPABASE_PROJECT_URL` **or** `SUPABASE_URL`; `SUPABASE_RAW_BUCKET` **or** `RAW_BUCKET`; `SUPABASE_PUBLISHED_BUCKET` **or** `PUBLISHED_BUCKET`. For CLI `secrets set`, put bucket names in `RAW_BUCKET` and `PUBLISHED_BUCKET` (matching the values in `backend/.env.local` for the `SUPABASE_*_BUCKET` variables). Without these, upload and council-layer handlers fail at runtime.

`backend/supabase/config.toml` uses one `[functions.<name>]` table per function with `verify_jwt = false` (current CLI rejects a global `[functions] verify_jwt = …` bool). Auth is **API key to Neon**, not Supabase JWT (review before production hardening).

---

## Security and rotation

**Principles**

- Repo contains **names and examples only**; production values live in password managers, Supabase/Neon dashboards, and GitHub Secrets.
- Never commit `backend/.env`, `backend/.env.local`, or root `.env*` with secrets (verify `.gitignore`).

**API key rotation**

1. Insert new `api_keys` row with new hash; test client with new secret.
2. Revoke or delete old row.
3. Update device DataStore / CI secrets as needed.

**`SUPABASE_SECRET_KEY` rotation**

- Generate new service role key in Supabase; update Edge secrets and GitHub Actions; redeploy functions.

**Neon password / connection string**

- Rotate in Neon console; update `DATABASE_URL` / `_POOLED` everywhere; restart workers.

**Upload transport**

- Prefer **short-lived signed PUT URLs** for uploads; device still holds a long-lived upload key in alpha—document risk in Settings; consider token exchange in a later phase.

---

## First rollout checklist

1. Run SQL migrations on Neon branch.
2. Create buckets and Edge secrets.
3. Deploy functions; smoke `healthz`.
4. **Seed one pilot council in Neon** (see below): real LGA boundary GeoJSON, canonical road-pack storage key, device row, API keys. Script: `backend/scripts/seed_pilot_council.py` — full operator walkthrough in [pilot-readiness.md](pilot-readiness.md).
5. Run `python backend/scripts/pilot_preflight.py --council-slug <slug>` (no `--skip-db` until the row checks pass).
6. Give the printed **DEVICE_UPLOAD** secret to the app once; store **COUNCIL_READ** for GIS consumers (password manager).
7. **Produce the real GeoJSON pack:** run `backend/roadpack-build/build_road_pack.py` after the boundary exists (uploads to Storage and upserts `road_packs`, including checksum). Until then, preflight may warn about `seed-placeholder-checksum`.
8. **Sideload on device:** copy `public-roads.geojson` into app-private storage  
   `filesDir/road_packs/{councilSlug}/{version}/public-roads.geojson`  
   (same path segments as the Storage object key, under the app sandbox). Optional `pack.json` in that folder may include `"version"`. The app picks the **lexicographically last** version directory name when multiple exist.
9. Field-test signed PUT size/latency on target devices.

### Pilot seed: one council (contract you must not drift from)

The hosted alpha stack assumes **exactly this** raw-object layout for the road pack (same as `build_road_pack.py`):

`roadpacks/<council-slug>/<road-pack-version>/public-roads.geojson`

- **`--council-slug`** and **`--project-slug`:** lowercase, digits, single hyphens (e.g. `bayside`, `alpha`). The script rejects empty or sloppy slugs.
- **`--boundary-geojson`:** one Polygon or MultiPolygon in WGS84 (EPSG:4326), either root geometry or a **single-feature** `FeatureCollection`. The script checks ring size, non-empty rings, and longitude/latitude ranges.
- **`--road-pack-storage-key`:** must **equal** `roadpacks/<same-council-slug>/<same-version>/public-roads.geojson` (no alternate prefixes in this pilot slice).
- **`--road-pack-version`:** must match the `<version>` segment above (e.g. `1.0.0`).
- **`--stable-install-id`:** non-empty; must match how the device row is keyed (`devices.stable_install_id`, unique per project). If this is wrong, upload auth will not line up with the seeded device.
- **`DATABASE_URL`** (or `DATABASE_URL_POOLED`) must be set (typically `backend/.env.local`).

After a successful run, the script prints **council slug, project slug, both API keys, road-pack version, storage key, UUIDs**, and **copy-paste next commands** (`pilot_preflight`, `pilot_smoke_e2e`).

---

## Related

- [docs/pilot-readiness.md](pilot-readiness.md)
- [docs/api-contract.md](api-contract.md)
- [docs/council-publishing.md](council-publishing.md)
- [docs/backend.md](backend.md)
