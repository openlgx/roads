#!/usr/bin/env python3
"""
Download NSW LGA boundaries (Geoscape Administrative Boundaries via data.gov.au),
extract the Yass Valley Council polygon, reproject GDA2020 → WGS84 (EPSG:4326),
and write a single-feature GeoJSON suitable for seed_pilot_council.py.

Official dataset: "NSW Local Government Areas - Geoscape Administrative Boundaries"
https://data.gov.au/data/dataset/nsw-local-government-areas

Zip resource (GDA2020, Feb 2026 release at time of writing):
https://data.gov.au/data/dataset/f6a00643-1842-48cd-9c2f-df23a3a1dc1e/resource/8935af35-9be5-4467-8752-f8a056777d5e/download/nsw_lga_gda2020.zip

Attribution (CC BY 4.0): Administrative Boundaries © Geoscape Australia — see dataset page.

Prerequisites: pyshp, pyproj, shapely
  pip install pyshp pyproj shapely
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"
DEFAULT_OUT = BACKEND / "sql" / "seeds" / "yass-valley-lga-authoritative.geojson"

LGA_ZIP_URL = (
    "https://data.gov.au/data/dataset/f6a00643-1842-48cd-9c2f-df23a3a1dc1e/"
    "resource/8935af35-9be5-4467-8752-f8a056777d5e/download/nsw_lga_gda2020.zip"
)
TARGET_NAME = "Yass Valley Council"
PRJ_EPSG = "EPSG:7844"  # GDA2020 geographic; matches bundled nsw_lga.prj


def _die(msg: str) -> int:
    print(f"[fetch_yass_valley_lga_geojson] {msg}", file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUT,
        help=f"Output GeoJSON path (default: {DEFAULT_OUT})",
    )
    args = parser.parse_args()

    try:
        import shapefile
        import pyproj
        from shapely.geometry import shape as shp_shape
        from shapely.ops import transform
    except ImportError as e:
        return _die(f"Missing dependency: {e} — pip install pyshp pyproj shapely")

    try:
        from urllib.request import urlretrieve
    except ImportError:
        return _die("urllib not available")

    out_path: Path = args.output
    out_path.parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="nsw_lga_") as tmp:
        zip_path = Path(tmp) / "nsw_lga_gda2020.zip"
        print(f"Downloading: {LGA_ZIP_URL}", flush=True)
        urlretrieve(LGA_ZIP_URL, zip_path)
        extract_dir = Path(tmp) / "extracted"
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(extract_dir)
        shp_files = list(extract_dir.rglob("*.shp"))
        if len(shp_files) != 1:
            return _die(f"Expected one .shp in zip, found {len(shp_files)}")
        shp_file = shp_files[0]

        reader = shapefile.Reader(str(shp_file))
        try:
            idx: int | None = None
            for i, rec in enumerate(reader.records()):
                raw = rec.get("LGA_NAME") if hasattr(rec, "get") else rec["LGA_NAME"]
                name = (raw or "").strip() if isinstance(raw, str) else str(raw).strip()
                if name == TARGET_NAME:
                    idx = i
                    break
            if idx is None:
                return _die(f"LGA not found: {TARGET_NAME!r}")

            sh = reader.shape(idx)
            if not hasattr(sh, "__geo_interface__"):
                return _die("pyshp shape has no __geo_interface__ — upgrade pyshp")
            transformer = pyproj.Transformer.from_crs(
                PRJ_EPSG, "EPSG:4326", always_xy=True
            )
            geom = shp_shape(sh.__geo_interface__)
            geom_wgs = transform(transformer.transform, geom)
            if geom_wgs.is_empty:
                return _die("Projected geometry is empty")
            if not geom_wgs.is_valid:
                geom_wgs = geom_wgs.buffer(0)
            gtype = geom_wgs.geom_type
            if gtype not in ("Polygon", "MultiPolygon"):
                return _die(f"Expected Polygon/MultiPolygon, got {gtype}")

            mapping = json.loads(json.dumps(geom_wgs.__geo_interface__))
        finally:
            reader.close()

        feature_coll = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "properties": {
                        "LGA_NAME": TARGET_NAME,
                        "source": "data.gov.au NSW Local Government Areas (Geoscape)",
                        "crs_source": PRJ_EPSG,
                        "crs_target": "EPSG:4326",
                    },
                    "geometry": mapping,
                }
            ],
        }
        out_path.write_text(
            json.dumps(feature_coll, indent=2),
            encoding="utf-8",
        )

    print(f"Wrote {out_path} ({gtype}, WGS84)", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
