"""
Build road pack: clip source GeoJSON lines to authoritative LGA from Neon, upload to Storage, register road_packs.

Fails closed without boundary geometry.
"""
from __future__ import annotations

import hashlib
import json
import os
import sys
import uuid
from pathlib import Path

import httpx
import psycopg
from shapely.geometry import shape, mapping

try:
    from shapely import from_geojson as shp_from_geojson
except ImportError:
    shp_from_geojson = None


def load_env():
    p = Path(__file__).resolve().parents[1] / ".env.local"
    if p.is_file():
        from dotenv import load_dotenv

        load_dotenv(p)


def connect():
    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL not set", file=sys.stderr)
        sys.exit(1)
    return psycopg.connect(dsn)


def parse_boundary(geojson_str: str):
    if shp_from_geojson:
        return shp_from_geojson(geojson_str)
    return shape(json.loads(geojson_str))


def main():
    load_env()
    council_slug = os.environ.get("COUNCIL_SLUG", "").strip()
    version = os.environ.get("PACK_VERSION", "").strip()
    source_path = os.environ.get("SOURCE_GEOJSON", "").strip()
    source_name = os.environ.get("SOURCE_NAME", "manual-import")
    source_version = os.environ.get("SOURCE_VERSION", "unknown")

    if not council_slug or not version or not source_path:
        print("Set COUNCIL_SLUG, PACK_VERSION, SOURCE_GEOJSON", file=sys.stderr)
        sys.exit(1)

    raw_bucket = os.environ.get("SUPABASE_RAW_BUCKET", "roads-alpha-raw")

    with open(source_path, encoding="utf-8") as f:
        src_fc = json.load(f)

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT c.id, ST_AsGeoJSON(l.geometry)::text
                FROM councils c
                JOIN lga_boundaries l ON l.council_id = c.id
                WHERE c.slug = %s AND l.geometry IS NOT NULL
                """,
                # citext compare
                (council_slug,),
            )
            row = cur.fetchone()
            if not row:
                print("No LGA boundary — fail-closed", file=sys.stderr)
                sys.exit(2)
            council_id, boundary_geojson = row[0], row[1]
            boundary = parse_boundary(boundary_geojson)

            feats = []
            for feat in src_fc.get("features", []):
                g = shape(feat["geometry"])
                if not g.is_valid:
                    g = g.buffer(0)
                inter = g.intersection(boundary)
                if inter.is_empty:
                    continue
                feats.append(
                    {
                        "type": "Feature",
                        "geometry": mapping(inter),
                        "properties": feat.get("properties", {}),
                    }
                )

            out_fc = {"type": "FeatureCollection", "features": feats}
            body = json.dumps(out_fc, sort_keys=True, separators=(",", ":")).encode("utf-8")
            digest = hashlib.sha256(body).hexdigest()
            storage_key = f"roadpacks/{council_slug}/{version}/public-roads.geojson"

            cur.execute(
                "SELECT id FROM projects WHERE council_id = %s::uuid LIMIT 1", (str(council_id),)
            )
            pr = cur.fetchone()
            if not pr:
                print("No project for council", file=sys.stderr)
                sys.exit(3)
            project_id = str(pr[0])

    url = os.environ["SUPABASE_PROJECT_URL"].rstrip("/")
    key = os.environ["SUPABASE_SECRET_KEY"]
    path = f"/storage/v1/object/{raw_bucket}/{storage_key}"
    headers = {
        "Authorization": f"Bearer {key}",
        "apikey": key,
        "Content-Type": "application/geo+json",
        "x-upsert": "true",
    }
    with httpx.Client(timeout=120.0) as client:
        r = client.post(f"{url}{path}", content=body, headers=headers)
        r.raise_for_status()

    with connect() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO road_packs (
                  council_id, project_id, version, source_name, source_version,
                  storage_key, checksum, format
                ) VALUES (%s::uuid, %s::uuid, %s, %s, %s, %s, %s, 'geojson')
                ON CONFLICT (council_id, version) DO UPDATE SET
                  storage_key = EXCLUDED.storage_key,
                  checksum = EXCLUDED.checksum,
                  updated_at = now()
                """,
                (
                    str(council_id),
                    project_id,
                    version,
                    source_name,
                    source_version,
                    storage_key,
                    digest,
                ),
            )
        conn.commit()
    print(f"Registered road_pack {storage_key} sha256={digest}")


if __name__ == "__main__":
    main()
