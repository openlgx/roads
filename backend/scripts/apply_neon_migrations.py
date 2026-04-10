#!/usr/bin/env python3
"""
Apply numbered SQL migrations in order to DATABASE_URL (Neon).

Tracks applied files in public._olgx_neon_applied_migrations so re-runs skip
already-applied migrations (scripts in backend/sql/migrations remain idempotent).

Usage (repo root or backend):
  python backend/scripts/apply_neon_migrations.py
  python backend/scripts/apply_neon_migrations.py --dry-run

Loads backend/.env.local when present (python-dotenv).
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from urllib.parse import urlparse

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"
MIG_DIR = BACKEND / "sql" / "migrations"
MIG_TABLE = "_olgx_neon_applied_migrations"


def _load_dotenv() -> None:
    p = BACKEND / ".env.local"
    if not p.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:
        print("pip install python-dotenv", file=sys.stderr)
        sys.exit(1)
    load_dotenv(p)


def _safe_dsn_target(dsn: str) -> str:
    """Hostname only — never print user, password, or path tokens."""
    try:
        u = urlparse(dsn)
        host = u.hostname or "(no host in DATABASE_URL)"
        port = f":{u.port}" if u.port else ""
        return f"{host}{port}"
    except Exception:
        return "(could not parse DATABASE_URL — check format)"


def _ensure_tracking_table(dsn: str) -> None:
    ddl = f"""
CREATE TABLE IF NOT EXISTS {MIG_TABLE} (
  filename text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);
"""
    import psycopg

    with psycopg.connect(dsn, connect_timeout=60, autocommit=True) as conn:
        conn.execute(ddl)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Apply backend/sql/migrations/*.sql to Neon in sorted order.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print migration files only; no DB")
    args = parser.parse_args()
    _load_dotenv()
    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL or DATABASE_URL_POOLED required", file=sys.stderr)
        return 1

    files = sorted(MIG_DIR.glob("*.sql"))
    if not files:
        print(f"No migrations in {MIG_DIR}", file=sys.stderr)
        return 1

    if args.dry_run:
        for f in files:
            print(f.name)
        return 0

    try:
        import psycopg
    except ImportError:
        print("pip install psycopg[binary]", file=sys.stderr)
        return 1

    target = _safe_dsn_target(dsn)
    print(f"Neon target (hostname from DSN): {target}", flush=True)

    try:
        _ensure_tracking_table(dsn)
    except Exception as e:
        print(
            f"Could not create or access {MIG_TABLE}: {e}\n"
            "Check DATABASE_URL points at your Neon branch, network, and credentials.",
            file=sys.stderr,
        )
        return 1

    applied = 0
    skipped = 0
    for f in files:
        try:
            with psycopg.connect(dsn, connect_timeout=60) as conn:
                with conn.cursor() as cur:
                    cur.execute(
                        f"SELECT 1 FROM {MIG_TABLE} WHERE filename = %s",
                        (f.name,),
                    )
                    if cur.fetchone():
                        print(f"Skip   {f.name} (already applied)", flush=True)
                        skipped += 1
                        continue

                sql = f.read_text(encoding="utf-8")
                print(f"Apply  {f.name} ...", flush=True)
                with conn.transaction():
                    conn.execute(sql)
                    with conn.cursor() as cur:
                        cur.execute(
                            f"INSERT INTO {MIG_TABLE} (filename) VALUES (%s)",
                            (f.name,),
                        )
                applied += 1
        except psycopg.OperationalError as e:
            print(
                f"FAILED {f.name}: database connection failed — {e}\n"
                "Verify DATABASE_URL / DATABASE_URL_POOLED matches the intended Neon branch.",
                file=sys.stderr,
            )
            return 1
        except psycopg.Error as e:
            print(
                f"FAILED {f.name}: {e}\n"
                "If this is the wrong database or branch, fix DATABASE_URL and retry.",
                file=sys.stderr,
            )
            return 1

    print(
        f"Migrations finished: {applied} applied, {skipped} already applied "
        f"({len(files)} files under sql/migrations).",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
