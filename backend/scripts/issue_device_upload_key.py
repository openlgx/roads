#!/usr/bin/env python3
"""
Issue or rotate a DEVICE_UPLOAD API key for an existing Neon (project_id, device_id) pair.

Plaintext is printed once — add to:
  - backend/.env.local as UPLOAD_API_KEY (for pilot_smoke_e2e / PILOT_DEVICE_UPLOAD_KEY)
  - repo root local.properties as roads.pilot.upload.key=... (for Android Gradle / BuildConfig)
  - or PilotBootstrapConfig.HARDCODED_DEVICE_UPLOAD_API_KEY (not recommended)

Does NOT use SUPABASE_SECRET_KEY (that is server-only).

Prerequisites:
  pip install psycopg[binary] python-dotenv
  backend/.env.local with DATABASE_URL

Example:
  python backend/scripts/issue_device_upload_key.py
  python backend/scripts/issue_device_upload_key.py --project-id ... --device-id ...
"""
from __future__ import annotations

import argparse
import hashlib
import secrets
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"

# Defaults match Android PilotBootstrapConfig (Yaas Valley alpha pilot).
DEFAULT_PROJECT_ID = "218a7453-ce83-429b-b42a-fb188abb0bb0"
DEFAULT_DEVICE_ID = "63b3ac61-8f1e-40bf-b377-195565e9f886"


def _load_dotenv_local() -> None:
    env_local = BACKEND / ".env.local"
    if not env_local.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:
        print("WARN: python-dotenv not installed", file=sys.stderr)
        return
    load_dotenv(env_local)


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def main() -> int:
    _load_dotenv_local()
    import os

    p = argparse.ArgumentParser(description="Mint DEVICE_UPLOAD key in Neon for pilot device.")
    p.add_argument("--project-id", default=DEFAULT_PROJECT_ID, help="Neon projects.id UUID")
    p.add_argument("--device-id", default=DEFAULT_DEVICE_ID, help="Neon devices.id UUID")
    p.add_argument(
        "--no-revoke",
        action="store_true",
        help="Do not revoke existing active DEVICE_UPLOAD keys for this device (not recommended).",
    )
    args = p.parse_args()
    project_id = args.project_id.strip()
    device_id = args.device_id.strip()

    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn or not dsn.strip():
        print("DATABASE_URL or DATABASE_URL_POOLED must be set (backend/.env.local).", file=sys.stderr)
        return 1

    plaintext = "olgx_du_" + secrets.token_urlsafe(24)
    key_hash = _sha256_hex(plaintext)

    try:
        import psycopg
    except ImportError:
        print("Install: pip install 'psycopg[binary]' python-dotenv", file=sys.stderr)
        return 1

    try:
        with psycopg.connect(dsn.strip(), connect_timeout=30) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id FROM devices
                    WHERE id = %s::uuid AND project_id = %s::uuid
                    LIMIT 1
                    """,
                    (device_id, project_id),
                )
                if cur.fetchone() is None:
                    print(
                        "No row in devices for this project_id + device_id. "
                        "Seed the pilot first (seed_pilot_council.py) or pass matching UUIDs.",
                        file=sys.stderr,
                    )
                    return 1

                if not args.no_revoke:
                    cur.execute(
                        """
                        UPDATE api_keys
                        SET status = 'revoked'
                        WHERE key_type = 'DEVICE_UPLOAD'
                          AND project_id = %s::uuid
                          AND device_id = %s::uuid
                          AND status = 'active'
                        """,
                        (project_id, device_id),
                    )

                cur.execute(
                    """
                    INSERT INTO api_keys (council_id, project_id, device_id, key_hash, key_type, status)
                    VALUES (NULL, %s::uuid, %s::uuid, %s, 'DEVICE_UPLOAD', 'active')
                    """,
                    (project_id, device_id, key_hash),
                )
            conn.commit()
    except psycopg.Error as e:
        print(f"Database error: {e}", file=sys.stderr)
        return 1

    print("")
    print("========== DEVICE_UPLOAD issued ==========")
    print(f"project_id: {project_id}")
    print(f"device_id:  {device_id}")
    print("")
    print("Plaintext (Authorization: Bearer ...) — store once:")
    print(plaintext)
    print("")
    print("Suggested: add to backend/.env.local:")
    print(f"UPLOAD_API_KEY={plaintext}")
    print("")
    print("Suggested: add to repo root local.properties (gitignored):")
    print(f"roads.pilot.upload.key={plaintext}")
    print("")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
