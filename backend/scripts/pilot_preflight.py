#!/usr/bin/env python3
"""
Pilot preflight: human-readable checks for env files, core secrets, and (optionally) DB seed rows.

Exit codes:
  0 — all required checks passed (warnings may still print to stdout)
  1 — one or more blocking checks failed (see stderr summary)

See docs/pilot-readiness.md.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"

# Non-zero connect timeout so hung Neon does not stall operators indefinitely
_DB_CONNECT_TIMEOUT_S = 30


def _load_dotenv_local() -> None:
    env_local = BACKEND / ".env.local"
    if not env_local.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:
        print("WARN: python-dotenv not installed; skipping backend/.env.local load", file=sys.stderr)
        return
    load_dotenv(env_local)


def _need(name: str) -> str | None:
    v = os.environ.get(name)
    if v is None or not str(v).strip():
        return None
    return str(v).strip()


def _check_db_seed(council_slug: str) -> tuple[list[str], list[str]]:
    errs: list[str] = []
    warns: list[str] = []
    slug_l = council_slug.strip().lower()
    dsn = _need("DATABASE_URL") or _need("DATABASE_URL_POOLED")
    if not dsn:
        errs.append("DATABASE_URL / DATABASE_URL_POOLED not set — cannot verify Neon (migrations, seed, workers)")
        return errs, warns
    try:
        import psycopg
    except ImportError:
        errs.append("psycopg not installed — pip install psycopg[binary] for DB checks")
        return errs, warns
    try:
        with psycopg.connect(dsn, connect_timeout=_DB_CONNECT_TIMEOUT_S) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT COUNT(*)::int FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name IN (
                      'councils', 'upload_jobs', 'lga_boundaries', 'road_packs', 'api_keys'
                    )
                    """,
                )
                n_core = cur.fetchone()[0]
                if n_core == 0:
                    errs.append(
                        "Neon public schema looks empty (core OLGX tables missing) — "
                        "run: python backend/scripts/apply_neon_migrations.py"
                    )
                    return errs, warns

                cur.execute(
                    """
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'councils'
                    LIMIT 1
                    """,
                )
                if not cur.fetchone():
                    errs.append(
                        "Neon: councils table missing — apply migrations: "
                        "python backend/scripts/apply_neon_migrations.py"
                    )
                    return errs, warns

                cur.execute(
                    """
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'upload_jobs'
                    LIMIT 1
                    """,
                )
                if not cur.fetchone():
                    errs.append(
                        "Neon: upload_jobs missing — apply migrations in order from backend/sql/migrations "
                        "(0001, 0002, 0003); script: python backend/scripts/apply_neon_migrations.py"
                    )
                    return errs, warns

                cur.execute(
                    """
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'upload_jobs'
                      AND column_name = 'object_key'
                    LIMIT 1
                    """,
                )
                if not cur.fetchone():
                    errs.append(
                        "Neon: upload_jobs.object_key missing — run "
                        "backend/sql/migrations/0003_upload_jobs_metadata.sql after 0001 and 0002 "
                        "(or re-run apply_neon_migrations.py)"
                    )
                    return errs, warns

                cur.execute(
                    "SELECT id::text FROM councils WHERE slug = lower(%s) LIMIT 1",
                    (council_slug,),
                )
                r = cur.fetchone()
                if not r:
                    errs.append(
                        f"council slug {council_slug!r} not found in Neon — run "
                        f"backend/scripts/seed_pilot_council.py with this slug after migrations"
                    )
                    return errs, warns
                cid = r[0]
                cur.execute(
                    "SELECT 1 FROM lga_boundaries WHERE council_id = %s::uuid AND geometry IS NOT NULL LIMIT 1",
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append(
                        "lga_boundaries row missing or geometry NULL — publish_council_layers fails closed "
                        "without an authoritative boundary; re-seed or fix geometry in Neon"
                    )
                cur.execute(
                    "SELECT 1 FROM projects WHERE council_id = %s::uuid LIMIT 1",
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append("projects row missing for council")
                cur.execute(
                    """
                    SELECT rp.storage_key, rp.version, rp.checksum, rp.project_id, rp.council_id,
                           p.council_id AS project_council_id
                    FROM road_packs rp
                    LEFT JOIN projects p ON p.id = rp.project_id
                    WHERE rp.council_id = %s::uuid
                    LIMIT 1
                    """,
                    (cid,),
                )
                rp_row = cur.fetchone()
                if not rp_row:
                    errs.append(
                        "road_packs row missing for council — seed with seed_pilot_council.py "
                        "or register storage_key/version after build_road_pack.py"
                    )
                else:
                    sk, ver, csum, rp_proj_id, rp_cid, proj_council_id = rp_row
                    if rp_proj_id is None:
                        errs.append(
                            "road_packs.project_id is null — pilot seed must tie the pack to "
                            "the council's project (same row build_road_pack uses)"
                        )
                    elif proj_council_id is None:
                        errs.append(
                            "road_packs.project_id points at a missing projects row — database inconsistent"
                        )
                    elif str(rp_cid) != str(proj_council_id):
                        errs.append(
                            "road_packs.project_id belongs to a different council than "
                            "road_packs.council_id (scoping inconsistent)"
                        )
                    if not sk or not str(sk).strip():
                        errs.append("road_packs.storage_key is null or empty")
                    if not ver or not str(ver).strip():
                        errs.append("road_packs.version is null or empty")
                    if sk and ver:
                        expected = (
                            f"roadpacks/{slug_l}/{str(ver).strip()}/public-roads.geojson"
                        )
                        if str(sk).strip() != expected:
                            errs.append(
                                f"road_packs.storage_key must be {expected!r} for slug/version "
                                f"(got {sk!r}) — must match build_road_pack.py convention"
                            )
                    if (
                        csum
                        and str(csum).strip().lower() == "seed-placeholder-checksum"
                    ):
                        warns.append(
                            "road_packs.checksum is still seed-placeholder-checksum — "
                            "update after uploading real pack (build_road_pack sets SHA-256)"
                        )
                cur.execute(
                    """
                    SELECT 1 FROM api_keys k
                    JOIN devices d ON d.id = k.device_id
                    JOIN projects p ON p.id = d.project_id
                    WHERE k.key_type = 'DEVICE_UPLOAD' AND k.status = 'active'
                      AND p.council_id = %s::uuid
                    LIMIT 1
                    """,
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append(
                        "no active DEVICE_UPLOAD key for this council's devices — re-run seed "
                        "or insert api_keys linked to devices under this council's project"
                    )
                cur.execute(
                    """
                    SELECT 1 FROM api_keys
                    WHERE key_type = 'COUNCIL_READ' AND status = 'active'
                      AND council_id = %s::uuid
                    LIMIT 1
                    """,
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append(
                        "no active COUNCIL_READ key scoped to this council_id — re-run seed "
                        "or issue a COUNCIL_READ api_keys row for this council"
                    )
    except Exception as e:
        errs.append(
            f"DB check failed (connection or query): {e} — verify DATABASE_URL branch, network, and that "
            "Neon allows connections from this machine"
        )
    return errs, warns


def _print_smoke_uuid_hint(council_slug: str) -> None:
    """Best-effort: print PILOT_PROJECT_ID / PILOT_DEVICE_ID for pilot_smoke_e2e."""
    dsn = _need("DATABASE_URL") or _need("DATABASE_URL_POOLED")
    if not dsn:
        return
    try:
        import psycopg
    except ImportError:
        return
    try:
        with psycopg.connect(dsn, connect_timeout=_DB_CONNECT_TIMEOUT_S) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT p.id::text, d.id::text
                    FROM councils c
                    JOIN projects p ON p.council_id = c.id
                    JOIN devices d ON d.project_id = p.id
                    WHERE c.slug = lower(%s)
                    ORDER BY d.created_at NULLS LAST
                    LIMIT 2
                    """,
                    (council_slug.strip().lower(),),
                )
                rows = cur.fetchall()
    except Exception:
        return
    if not rows:
        return
    if len(rows) > 1:
        print(
            "Smoke E2E: multiple devices for this council — pass explicit "
            "--project-id and --device-id (see seed output).",
            flush=True,
        )
        return
    pid, did = rows[0][0], rows[0][1]
    print("Smoke E2E device targeting (single-device councils):", flush=True)
    print(f"  PILOT_PROJECT_ID={pid}", flush=True)
    print(f"  PILOT_DEVICE_ID={did}", flush=True)


def _warn_optional_url_alignment() -> list[str]:
    """If UPLOAD_BASE_URL is set, ensure it matches Edge pattern (Android uses /functions/v1)."""
    warns: list[str] = []
    supa = _need("SUPABASE_PROJECT_URL")
    upload_base = _need("UPLOAD_BASE_URL")
    if upload_base:
        t = upload_base.rstrip("/")
        if "/functions" not in urlparse(t).path and not t.endswith("/functions/v1"):
            warns.append(
                "UPLOAD_BASE_URL should end with /functions/v1 (Supabase Edge); "
                "the Android app auto-appends this for *.supabase.co hosts when you save Settings."
            )
        if supa:
            try:
                shost = urlparse(supa).hostname or ""
                uhost = urlparse(upload_base).hostname or ""
                if shost and uhost and shost != uhost:
                    warns.append(
                        "UPLOAD_BASE_URL host differs from SUPABASE_PROJECT_URL host — "
                        "confirm both point at the same Supabase project."
                    )
            except Exception:
                pass
    return warns


def main() -> int:
    parser = argparse.ArgumentParser(description="OLGX Roads pilot preflight checks")
    parser.add_argument(
        "--council-slug",
        default=os.environ.get("PILOT_COUNCIL_SLUG", "olgx-pilot"),
        help="Council slug to verify in Neon (default: olgx-pilot or PILOT_COUNCIL_SLUG)",
    )
    parser.add_argument("--skip-db", action="store_true", help="Skip Neon row checks")
    args = parser.parse_args()

    _load_dotenv_local()
    errors: list[str] = []
    warnings: list[str] = []

    gitignore = REPO_ROOT / ".gitignore"
    if gitignore.is_file():
        text = gitignore.read_text(encoding="utf-8", errors="replace")
        if "backend/.env.local" not in text and ".env.local" not in text:
            warnings.append(".gitignore may not ignore backend/.env.local")

    env_example = BACKEND / ".env.example"
    if not env_example.is_file():
        errors.append("backend/.env.example missing")

    for key in (
        "SUPABASE_PROJECT_URL",
        "SUPABASE_SECRET_KEY",
        "SUPABASE_RAW_BUCKET",
        "SUPABASE_PUBLISHED_BUCKET",
    ):
        if not _need(key):
            errors.append(f"missing env {key} (set in backend/.env.local for scripts and Edge parity)")

    if not args.skip_db and not _need("DATABASE_URL") and not _need("DATABASE_URL_POOLED"):
        errors.append(
            "DATABASE_URL and DATABASE_URL_POOLED both unset — need at least one for migrations, "
            "seed, preflight DB checks, processing, and publish"
        )
    elif args.skip_db and not _need("DATABASE_URL") and not _need("DATABASE_URL_POOLED"):
        warnings.append(
            "DATABASE_URL not set — you used --skip-db; migrations/seed/workers still require Neon URL later"
        )

    wf = REPO_ROOT / ".github" / "workflows" / "processing-backfill.yml"
    if wf.is_file():
        body = wf.read_text(encoding="utf-8", errors="replace")
        if "DATABASE_URL" not in body:
            errors.append("processing-backfill.yml may be out of date (expected DATABASE_URL)")
        elif "backend/processing" not in body and "working-directory" not in body:
            warnings.append("processing-backfill.yml: verify working-directory still targets backend/processing")

    warnings.extend(_warn_optional_url_alignment())

    if not args.skip_db:
        db_errs, db_warns = _check_db_seed(args.council_slug)
        errors.extend(db_errs)
        warnings.extend(db_warns)

    print("", flush=True)
    print("---------- PILOT PREFLIGHT ----------", flush=True)
    for w in warnings:
        print(f"WARN: {w}", flush=True)
    if errors:
        print("", flush=True)
        print("SUMMARY: FAIL - blocking issue(s) above", file=sys.stderr, flush=True)
        print("PILOT PREFLIGHT FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        print(
            f"\nFix prerequisites, then re-run: python backend/scripts/pilot_preflight.py "
            f"--council-slug {args.council_slug.strip().lower()}",
            file=sys.stderr,
        )
        return 1
    print("SUMMARY: PASS (no blocking issues)", flush=True)
    print("PILOT PREFLIGHT OK", flush=True)
    print(f"  Council slug checked: {args.council_slug.strip().lower()}", flush=True)
    if not args.skip_db:
        _print_smoke_uuid_hint(args.council_slug)
    print("-------------------------------------", flush=True)
    print("", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
