#!/usr/bin/env python3
"""
Dump ALL hosted roughness + anomaly points as unclipped GeoJSON (no council boundary filter).

Writes two files next to this script's output dir:
  - all_roughness.geojson
  - all_anomalies.geojson

Usage (repo root):
  python backend/scripts/dump_all_hosted_geojson.py
  python backend/scripts/dump_all_hosted_geojson.py --out-dir C:\\some\\folder
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]


def _load_dotenv() -> None:
    p = BACKEND / ".env.local"
    if not p.is_file():
        return
    try:
        from dotenv import load_dotenv
        load_dotenv(p)
    except ImportError:
        print("pip install python-dotenv", file=sys.stderr)
        sys.exit(1)


def _payload_as_dict(payload) -> dict:
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, str):
        return json.loads(payload)
    return json.loads(json.dumps(payload))


def main() -> int:
    parser = argparse.ArgumentParser(description="Dump all hosted points as unclipped GeoJSON.")
    parser.add_argument("--out-dir", type=str, default=None,
                        help="Output directory (default: analysis_output/all_hosted)")
    args = parser.parse_args()

    _load_dotenv()
    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL not set", file=sys.stderr)
        return 1

    try:
        import psycopg
    except ImportError:
        print("pip install psycopg[binary]", file=sys.stderr)
        return 1

    out_dir = Path(args.out_dir) if args.out_dir else (BACKEND.parent / "analysis_output" / "all_hosted")
    out_dir.mkdir(parents=True, exist_ok=True)

    with psycopg.connect(dsn, connect_timeout=60) as conn:
        with conn.cursor() as cur:
            # Roughness
            cur.execute("""
                SELECT payload_json, recording_session_id::text
                FROM derived_window_features_hosted
                WHERE payload_json->'geometry' IS NOT NULL
                  AND payload_json->>'geometry' != 'null'
                ORDER BY recording_session_id,
                  COALESCE((payload_json->>'windowStartMs')::float, 0)
            """)
            roughness_rows = cur.fetchall()

            # Anomalies
            cur.execute("""
                SELECT payload_json, recording_session_id::text
                FROM anomaly_candidates_hosted
                WHERE payload_json->'geometry' IS NOT NULL
                  AND payload_json->>'geometry' != 'null'
                ORDER BY recording_session_id,
                  COALESCE((payload_json->>'windowStartMs')::float, 0)
            """)
            anomaly_rows = cur.fetchall()

            # LGA boundary for reference layer
            cur.execute("""
                SELECT c.slug::text, ST_AsGeoJSON(lb.geometry)::text
                FROM lga_boundaries lb
                JOIN councils c ON c.id = lb.council_id
                WHERE lb.geometry IS NOT NULL
            """)
            boundary_rows = cur.fetchall()

    def rows_to_fc(rows, label: str) -> dict:
        features = []
        for i, (payload, rsid) in enumerate(rows):
            pl = _payload_as_dict(payload)
            geom = pl.get("geometry")
            if not geom or geom.get("type") != "Point":
                continue
            props = {k: v for k, v in pl.items() if k != "geometry"}
            props["recordingSessionId"] = rsid
            features.append({"type": "Feature", "id": i, "geometry": geom, "properties": props})
        print(f"{label}: {len(features)} features from {len(set(r[1] for r in rows))} sessions")
        return {"type": "FeatureCollection", "features": features}

    rough_fc = rows_to_fc(roughness_rows, "Roughness")
    ano_fc = rows_to_fc(anomaly_rows, "Anomalies")

    rough_path = out_dir / "all_roughness.geojson"
    ano_path = out_dir / "all_anomalies.geojson"

    with open(rough_path, "w", encoding="utf-8") as f:
        json.dump(rough_fc, f, separators=(",", ":"))
    print(f"Wrote {rough_path}  ({rough_path.stat().st_size:,} bytes)")

    with open(ano_path, "w", encoding="utf-8") as f:
        json.dump(ano_fc, f, separators=(",", ":"))
    print(f"Wrote {ano_path}  ({ano_path.stat().st_size:,} bytes)")

    if boundary_rows:
        boundary_features = []
        for slug, geojson_str in boundary_rows:
            geom = json.loads(geojson_str)
            boundary_features.append({
                "type": "Feature",
                "geometry": geom,
                "properties": {"councilSlug": slug, "layer": "lga_boundary"},
            })
        boundary_fc = {"type": "FeatureCollection", "features": boundary_features}
        boundary_path = out_dir / "lga_boundaries.geojson"
        with open(boundary_path, "w", encoding="utf-8") as f:
            json.dump(boundary_fc, f, separators=(",", ":"))
        print(f"Wrote {boundary_path}  ({boundary_path.stat().st_size:,} bytes)")

    print(f"\nOpen in QGIS: drag all .geojson files from {out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
