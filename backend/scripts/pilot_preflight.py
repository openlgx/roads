#!/usr/bin/env python3
"""
Pilot preflight: human-readable checks for env files, core secrets, and (optionally) DB seed rows.
Exit 1 on failure. See docs/pilot-readiness.md.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"


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


def _check_db_seed(council_slug: str) -> list[str]:
    errs: list[str] = []
    dsn = _need("DATABASE_URL") or _need("DATABASE_URL_POOLED")
    if not dsn:
        errs.append("DATABASE_URL not set — cannot verify council seed in Neon")
        return errs
    try:
        import psycopg
    except ImportError:
        errs.append("psycopg not installed — pip install psycopg[binary] for DB checks")
        return errs
    try:
        with psycopg.connect(dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id::text FROM councils WHERE slug = lower(%s) LIMIT 1",
                    (council_slug,),
                )
                r = cur.fetchone()
                if not r:
                    errs.append(f"council slug {council_slug!r} not found")
                    return errs
                cid = r[0]
                cur.execute(
                    "SELECT 1 FROM lga_boundaries WHERE council_id = %s::uuid AND geometry IS NOT NULL LIMIT 1",
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append("lga_boundaries row missing or geometry NULL (publish will fail closed)")
                cur.execute(
                    "SELECT 1 FROM projects WHERE council_id = %s::uuid LIMIT 1",
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append("project row missing for council")
                cur.execute(
                    "SELECT 1 FROM road_packs WHERE council_id = %s::uuid LIMIT 1",
                    (cid,),
                )
                if not cur.fetchone():
                    errs.append("road_packs registration missing for council")
                cur.execute(
                    """
                    SELECT 1 FROM api_keys
                    WHERE key_type = 'DEVICE_UPLOAD' AND status = 'active' LIMIT 1
                    """,
                )
                if not cur.fetchone():
                    errs.append("no active DEVICE_UPLOAD api_keys row (any council)")
                cur.execute(
                    """
                    SELECT 1 FROM api_keys
                    WHERE key_type = 'COUNCIL_READ' AND status = 'active' LIMIT 1
                    """,
                )
                if not cur.fetchone():
                    errs.append("no active COUNCIL_READ api_keys row (any council)")
    except Exception as e:
        errs.append(f"DB check failed: {e}")
    return errs


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
            errors.append(f"missing env {key}")

    if not _need("DATABASE_URL") and not _need("DATABASE_URL_POOLED"):
        warnings.append("DATABASE_URL not set (needed for migrations, publish, processing, DB preflight)")

    wf = REPO_ROOT / ".github" / "workflows" / "processing-backfill.yml"
    if wf.is_file():
        body = wf.read_text(encoding="utf-8", errors="replace")
        if "DATABASE_URL" not in body or "backend/processing" not in body:
            errors.append("processing-backfill.yml may be out of date")

    if not args.skip_db:
        errors.extend(_check_db_seed(args.council_slug))

    for w in warnings:
        print(f"WARN: {w}")
    if errors:
        print("PILOT PREFLIGHT FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print("PILOT PREFLIGHT OK")
    print(f"  Council slug checked: {args.council_slug}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
