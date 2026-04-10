#!/usr/bin/env python3
"""
Practical pilot smoke checks against live Supabase Edge + Neon (requires real secrets in env).
Does not upload a full bundle by default — use pilot_one_session.md flow for a real ZIP.

Usage (PowerShell):
  cd backend
  pip install httpx python-dotenv psycopg[binary]
  copy .env.example .env.local  # then fill secrets
  python scripts/pilot_smoke_e2e.py --council-slug YOUR_SLUG
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def _load_env() -> None:
    p = REPO_ROOT / "backend" / ".env.local"
    if not p.is_file():
        return
    from dotenv import load_dotenv

    load_dotenv(p)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--council-slug", required=True)
    parser.add_argument(
        "--device-upload-key",
        default=os.environ.get("PILOT_DEVICE_UPLOAD_KEY", ""),
        help="Plaintext DEVICE_UPLOAD key (or set PILOT_DEVICE_UPLOAD_KEY)",
    )
    parser.add_argument(
        "--council-read-key",
        default=os.environ.get("PILOT_COUNCIL_READ_KEY", ""),
        help="Plaintext COUNCIL_READ key (or set PILOT_COUNCIL_READ_KEY)",
    )
    args = parser.parse_args()
    _load_env()

    base = os.environ.get("SUPABASE_PROJECT_URL", "").rstrip("/")
    if not base:
        print("SUPABASE_PROJECT_URL missing", file=sys.stderr)
        return 1

    try:
        import httpx
    except ImportError:
        print("pip install httpx", file=sys.stderr)
        return 1

    failures: list[str] = []

    # healthz (no auth typically)
    hz = f"{base}/functions/v1/healthz"
    try:
        r = httpx.get(hz, timeout=30.0)
        # 503 when Edge cannot reach Neon is still proof the function URL is deployed.
        if r.status_code not in (200, 503):
            failures.append(f"healthz HTTP {r.status_code}")
    except Exception as e:
        failures.append(f"healthz: {e}")

    if args.council_read_key:
        mf = f"{base}/functions/v1/council-layers-manifest"
        try:
            r = httpx.get(
                mf,
                params={"councilSlug": args.council_slug},
                headers={"Authorization": f"Bearer {args.council_read_key}"},
                timeout=60.0,
            )
            if r.status_code == 404:
                failures.append("manifest 404 (publish may not have run yet — expected on fresh pilot)")
            elif r.status_code >= 400:
                failures.append(f"manifest HTTP {r.status_code} {r.text[:200]}")
            else:
                data = r.json()
                for k in ("manifestVersion", "councilSlug", "publishedAt", "publishRunId", "layerArtifacts"):
                    if k not in data:
                        failures.append(f"manifest missing field {k!r}")
        except Exception as e:
            failures.append(f"manifest: {e}")
    else:
        print("SKIP manifest: set --council-read-key or PILOT_COUNCIL_READ_KEY")

    if args.device_upload_key:
        # Lightweight validation: POST uploads-create with intentionally invalid UUIDs should be 400, not 5xx
        uc = f"{base}/functions/v1/uploads-create"
        try:
            r = httpx.post(
                uc,
                headers={
                    "Authorization": f"Bearer {args.device_upload_key}",
                    "Content-Type": "application/json",
                },
                json={"apiVersion": 1, "not": "valid"},
                timeout=60.0,
            )
            if r.status_code >= 500:
                failures.append(f"uploads-create unexpected {r.status_code}")
        except Exception as e:
            failures.append(f"uploads-create: {e}")
    else:
        print("SKIP uploads-create probe: set --device-upload-key or PILOT_DEVICE_UPLOAD_KEY")

    if failures:
        print("SMOKE FAILED:", file=sys.stderr)
        for f in failures:
            print(f"  - {f}", file=sys.stderr)
        return 1
    print("SMOKE OK (edge reachable; manifest contract checked if keys provided)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
