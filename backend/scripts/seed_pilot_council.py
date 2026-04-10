#!/usr/bin/env python3
"""
Seed one pilot council in Neon: council, project, LGA boundary (from GeoJSON file),
road_pack registration, device, DEVICE_UPLOAD + COUNCIL_READ API keys.

Plaintext keys are printed once — store in a password manager.

Prerequisites:
  pip install psycopg[binary] python-dotenv
  backend/.env.local with DATABASE_URL

Example:
  python scripts/seed_pilot_council.py \\
    --council-slug olgx-pilot \\
    --council-name "OLGX Pilot Council" \\
    --project-slug alpha \\
    --boundary-geojson path/to/lga.geojson \\
    --road-pack-storage-key roadpacks/olgx-pilot/1.0.0/public-roads.geojson \\
    --stable-install-id pilot-device-001
"""
from __future__ import annotations

import argparse
import hashlib
import json
import secrets
import sys
import uuid
from pathlib import Path


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--council-slug", required=True)
    parser.add_argument("--council-name", required=True)
    parser.add_argument("--project-slug", default="alpha")
    parser.add_argument("--project-name", default="Pilot project")
    parser.add_argument(
        "--boundary-geojson",
        required=True,
        help="Path to GeoJSON Polygon or MultiPolygon for LGA (WGS84)",
    )
    parser.add_argument("--boundary-source-name", default="pilot-import")
    parser.add_argument("--road-pack-version", default="1.0.0")
    parser.add_argument(
        "--road-pack-storage-key",
        required=True,
        help="Supabase raw/pack storage key for this pack (see docs)",
    )
    parser.add_argument("--road-pack-checksum", default="seed-placeholder-checksum")
    parser.add_argument("--stable-install-id", required=True)
    args = parser.parse_args()

    backend = Path(__file__).resolve().parents[1]
    env_local = backend / ".env.local"
    if env_local.is_file():
        from dotenv import load_dotenv

        load_dotenv(env_local)

    import os

    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL not set", file=sys.stderr)
        return 1

    geo_path = Path(args.boundary_geojson)
    if not geo_path.is_file():
        print(f"boundary file not found: {geo_path}", file=sys.stderr)
        return 1
    gj = json.loads(geo_path.read_text(encoding="utf-8"))
    if gj.get("type") == "FeatureCollection":
        feats = gj.get("features") or []
        if len(feats) != 1:
            print("Expected a single-feature FeatureCollection or raw Polygon", file=sys.stderr)
            return 1
        geom = feats[0].get("geometry")
    elif gj.get("type") in ("Polygon", "MultiPolygon"):
        geom = gj
    else:
        print("GeoJSON must be Polygon/MultiPolygon or single-feature FeatureCollection", file=sys.stderr)
        return 1
    geom_txt = json.dumps(geom, separators=(",", ":"))

    device_upload_secret = "olgx_du_" + secrets.token_urlsafe(24)
    council_read_secret = "olgx_cr_" + secrets.token_urlsafe(24)

    import psycopg

    council_id = str(uuid.uuid4())
    project_id = str(uuid.uuid4())
    device_id = str(uuid.uuid4())

    with psycopg.connect(dsn, autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO councils (id, slug, name)
                VALUES (%s::uuid, lower(%s), %s)
                """,
                (council_id, args.council_slug, args.council_name),
            )
            cur.execute(
                """
                INSERT INTO projects (id, council_id, slug, name, status)
                VALUES (%s::uuid, %s::uuid, lower(%s), %s, 'active')
                """,
                (project_id, council_id, args.project_slug, args.project_name),
            )
            cur.execute(
                """
                INSERT INTO lga_boundaries (council_id, source_name, geometry)
                VALUES (
                  %s::uuid,
                  %s,
                  ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(%s), 4326))
                )
                """,
                (council_id, args.boundary_source_name, geom_txt),
            )
            cur.execute(
                """
                INSERT INTO road_packs (
                  council_id, project_id, version, source_name, storage_key, checksum, format
                ) VALUES (
                  %s::uuid, %s::uuid, %s, %s, %s, %s, 'geojson_v1'
                )
                """,
                (
                    council_id,
                    project_id,
                    args.road_pack_version,
                    args.boundary_source_name,
                    args.road_pack_storage_key,
                    args.road_pack_checksum,
                ),
            )
            cur.execute(
                """
                INSERT INTO devices (
                  id, project_id, stable_install_id, platform,
                  manufacturer, model, app_version
                ) VALUES (
                  %s::uuid, %s::uuid, %s, 'android',
                  'pilot', 'seed', 'pilot-seed'
                )
                """,
                (device_id, project_id, args.stable_install_id),
            )
            cur.execute(
                """
                INSERT INTO api_keys (council_id, project_id, device_id, key_hash, key_type, status)
                VALUES (
                  NULL, %s::uuid, %s::uuid, %s, 'DEVICE_UPLOAD', 'active'
                )
                """,
                (project_id, device_id, _sha256_hex(device_upload_secret)),
            )
            cur.execute(
                """
                INSERT INTO api_keys (council_id, key_hash, key_type, status)
                VALUES (
                  %s::uuid, %s, 'COUNCIL_READ', 'active'
                )
                """,
                (council_id, _sha256_hex(council_read_secret)),
            )
        conn.commit()

    print("SEED OK")
    print(f"  council_id={council_id} slug={args.council_slug.lower()}")
    print(f"  project_id={project_id} slug={args.project_slug.lower()}")
    print(f"  device_id={device_id} stable_install_id={args.stable_install_id!r}")
    print("")
    print("DEVICE_UPLOAD key (give to Android uploadApiKey once):")
    print(f"  {device_upload_secret}")
    print("COUNCIL_READ key (QGIS/ArcGIS Authorization: Bearer …):")
    print(f"  {council_read_secret}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
