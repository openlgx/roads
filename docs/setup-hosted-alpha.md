# Setup: hosted alpha (Neon + Supabase + CI)

This guide is for **operators and contributors** wiring the ingestion/publish stack. The Android app **never** receives Neon database credentials; it only needs **`uploadBaseUrl`** (Supabase functions base) and a **`DEVICE_UPLOAD`** API key (see security notes).

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
# Example: psql against Neon (set DATABASE_URL first)
psql $env:DATABASE_URL -f backend/sql/migrations/0001_extensions.sql
```

Or use `backend/scripts/validate-migrations.ps1` for syntax smoke (see workflow `migration-check.yml`).

---

## Edge Functions deploy

```bash
cd backend
supabase link --project-ref <ref>
supabase functions deploy uploads-create
supabase functions deploy uploads-complete
supabase functions deploy healthz
supabase functions deploy council-layers-manifest
supabase functions deploy council-layers-roughness
supabase functions deploy council-layers-anomalies
supabase functions deploy council-layers-consensus
```

Configure function secrets in Supabase dashboard: `DATABASE_URL_POOLED`, `SUPABASE_SECRET_KEY`, `SUPABASE_PROJECT_URL`, bucket names.

`backend/supabase/config.toml` sets `verify_jwt = false` because auth is **API key to Neon**, not Supabase JWT (review before production hardening).

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
4. Seed **council**, **project**, **device**, **lga_boundaries** (real boundary required before publish). Automatable path: `python backend/scripts/seed_pilot_council.py` (see [pilot-readiness.md](pilot-readiness.md)).
5. Run `python backend/scripts/pilot_preflight.py --council-slug <slug>`.
6. Issue **DEVICE_UPLOAD** key to the app; **COUNCIL_READ** for GIS consumers.
7. Build and register **road pack** (`roadpack-build`) before expecting road-filtered uploads.
8. **Sideload on device:** copy `public-roads.geojson` into app-private storage  
   `filesDir/road_packs/{councilSlug}/{version}/public-roads.geojson`  
   (same path shape as the Storage key, under the app sandbox). Optional `pack.json` in that folder may include `"version"`. The app picks the **lexicographically last** version directory name when multiple exist.
9. Field-test signed PUT size/latency on target devices.

---

## Related

- [docs/pilot-readiness.md](pilot-readiness.md)
- [docs/api-contract.md](api-contract.md)
- [docs/council-publishing.md](council-publishing.md)
- [docs/backend.md](backend.md)
