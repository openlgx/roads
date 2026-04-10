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
import re
import secrets
import sys
import uuid
from pathlib import Path

_PLACEHOLDER_CHECKSUM = "seed-placeholder-checksum"
_SLUG_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
_PREFIX = "[seed_pilot_council]"


def _die(message: str) -> int:
    print(f"{_PREFIX} {message}", file=sys.stderr)
    return 1


def _sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def _normalize_slug(raw: str, label: str) -> tuple[str | None, int | None]:
    s = (raw or "").strip().lower()
    if not s:
        return None, _die(
            f"{label} is missing or empty. Pass a non-empty slug "
            f"(lowercase letters, digits, hyphen), e.g. --council-slug your-lga-slug."
        )
    if not _SLUG_RE.match(s):
        return None, _die(
            f"{label} {raw!r} is invalid. Use lowercase [a-z0-9] with single hyphens "
            f"between segments (no leading/trailing hyphen)."
        )
    return s, None


def _iter_ring_positions(polygon_coords: list) -> list[tuple[float, float]]:
    out: list[tuple[float, float]] = []
    for ring in polygon_coords:
        if not isinstance(ring, list):
            continue
        for pt in ring:
            if not isinstance(pt, (list, tuple)) or len(pt) < 2:
                continue
            out.append((float(pt[0]), float(pt[1])))
    return out


def _validate_wgs84_polygon_rings(polygon_coords: list, label: str) -> list[str]:
    errs: list[str] = []
    if not polygon_coords:
        errs.append(f"{label}: no rings")
        return errs
    exterior = polygon_coords[0]
    if not isinstance(exterior, list) or len(exterior) < 4:
        errs.append(
            f"{label}: exterior ring must have at least 4 positions "
            f"(closed ring in GeoJSON; got {len(exterior) if isinstance(exterior, list) else 0})"
        )
    try:
        pts = _iter_ring_positions(polygon_coords)
    except (TypeError, ValueError) as e:
        errs.append(f"{label}: coordinates must be numeric lng/lat pairs ({e})")
        return errs
    for lon, lat in pts:
        if not (-180.0 <= lon <= 180.0 and -90.0 <= lat <= 90.0):
            errs.append(
                f"{label}: position ({lon}, {lat}) is outside WGS84 bounds "
                f"(longitude [-180,180], latitude [-90,90])"
            )
            break
    return errs


def validate_boundary_geometry(geom: object) -> list[str]:
    """Require Polygon/MultiPolygon, non-empty rings, WGS84 lng/lat range."""
    errs: list[str] = []
    if not isinstance(geom, dict):
        return ["geometry must be a GeoJSON object"]
    gtype = geom.get("type")
    coords = geom.get("coordinates")
    if gtype == "Polygon":
        if not isinstance(coords, list):
            errs.append("Polygon.coordinates must be a list of rings")
            return errs
        errs.extend(_validate_wgs84_polygon_rings(coords, "Polygon"))
    elif gtype == "MultiPolygon":
        if not isinstance(coords, list) or not coords:
            errs.append("MultiPolygon.coordinates must be a non-empty list")
            return errs
        for i, poly in enumerate(coords):
            if not isinstance(poly, list):
                errs.append(f"MultiPolygon patch {i}: invalid coordinates")
                continue
            errs.extend(_validate_wgs84_polygon_rings(poly, f"MultiPolygon patch {i}"))
    else:
        errs.append(
            f"boundary geometry type must be Polygon or MultiPolygon (got {gtype!r})"
        )
    return errs


def expected_road_pack_storage_key(council_slug: str, version: str) -> str:
    return f"roadpacks/{council_slug}/{version}/public-roads.geojson"


def validate_road_pack_registration(
    council_slug: str, version: str, storage_key: str
) -> list[str]:
    errs: list[str] = []
    sk = (storage_key or "").strip()
    ver = (version or "").strip()
    if not sk:
        errs.append(
            "road-pack storage key is missing or whitespace-only. "
            "Use the same key shape as build_road_pack.py uploads, e.g. "
            f"{expected_road_pack_storage_key('your-council-slug', '1.0.0')!r}."
        )
    if not ver:
        errs.append(
            "road-pack version is missing or whitespace-only. "
            "Pass --road-pack-version (must match the directory segment in storage_key)."
        )
    if errs:
        return errs
    expected = expected_road_pack_storage_key(council_slug, ver)
    if sk != expected:
        errs.append(
            "road-pack storage_key does not match council slug + version. "
            f"Expected exactly: {expected!r} (got {sk!r}). "
            "This must align with backend/roadpack-build/build_road_pack.py and device path "
            "files/road_packs/<slug>/<version>/."
        )
    return errs


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
    parser.add_argument("--road-pack-checksum", default=_PLACEHOLDER_CHECKSUM)
    parser.add_argument("--stable-install-id", required=True)
    args = parser.parse_args()

    council_slug, err = _normalize_slug(args.council_slug, "Council slug (--council-slug)")
    if err is not None:
        return err
    project_slug, err = _normalize_slug(args.project_slug, "Project slug (--project-slug)")
    if err is not None:
        return err

    council_name = (args.council_name or "").strip()
    if not council_name:
        return _die(
            "--council-name must be non-empty (human-readable council title for Neon councils.name)."
        )

    install_id = (args.stable_install_id or "").strip()
    if not install_id:
        return _die(
            "--stable-install-id is missing or whitespace-only. "
            "Use a stable device/profile id that matches how the pilot device is keyed "
            "(see devices.stable_install_id); e.g. pilot-s21-field-01."
        )

    road_ver = (args.road_pack_version or "").strip()
    road_key = (args.road_pack_storage_key or "").strip()
    rp_errs = validate_road_pack_registration(council_slug, road_ver, road_key)
    for e in rp_errs:
        return _die(e)

    backend = Path(__file__).resolve().parents[1]
    env_local = backend / ".env.local"
    if env_local.is_file():
        from dotenv import load_dotenv

        load_dotenv(env_local)

    import os

    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        return _die(
            "DATABASE_URL (or DATABASE_URL_POOLED) is not set. "
            "Add it to backend/.env.local or export it so this script can connect to Neon."
        )

    geo_path = Path(args.boundary_geojson)
    if not geo_path.is_file():
        return _die(
            f"Boundary file not found: {geo_path.resolve()}. "
            f"Pass --boundary-geojson with the path to your authoritative LGA Polygon/MultiPolygon GeoJSON."
        )
    try:
        gj = json.loads(geo_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        return _die(f"Boundary file is not valid JSON: {geo_path} ({e})")
    if gj.get("type") == "FeatureCollection":
        feats = gj.get("features") or []
        if len(feats) != 1:
            return _die(
                "Boundary GeoJSON must be a single-feature FeatureCollection or a raw Polygon/MultiPolygon. "
                f"Found {len(feats)} features (need exactly 1)."
            )
        geom = feats[0].get("geometry")
        if not geom:
            return _die("Boundary feature has no geometry.")
    elif gj.get("type") in ("Polygon", "MultiPolygon"):
        geom = gj
    else:
        return _die(
            "Boundary GeoJSON must be Polygon, MultiPolygon, or a single-feature FeatureCollection "
            f"(got root type {gj.get('type')!r})."
        )

    b_errs = validate_boundary_geometry(geom)
    if b_errs:
        for e in b_errs:
            return _die(f"Boundary invalid: {e}")
    geom_txt = json.dumps(geom, separators=(",", ":"))

    checksum_use = (args.road_pack_checksum or "").strip()
    if checksum_use == _PLACEHOLDER_CHECKSUM:
        print(
            f"{_PREFIX} WARN: road-pack checksum is still the placeholder. After you run "
            f"backend/roadpack-build/build_road_pack.py, update the road_packs.checksum row in Neon "
            f"to the SHA-256 of the uploaded GeoJSON (or re-seed with --road-pack-checksum <sha256>).",
            file=sys.stderr,
        )

    device_upload_secret = "olgx_du_" + secrets.token_urlsafe(24)
    council_read_secret = "olgx_cr_" + secrets.token_urlsafe(24)

    import psycopg

    council_id = str(uuid.uuid4())
    project_id = str(uuid.uuid4())
    device_id = str(uuid.uuid4())

    try:
        with psycopg.connect(dsn, autocommit=False) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO councils (id, slug, name)
                    VALUES (%s::uuid, %s, %s)
                """,
                    (council_id, council_slug, council_name),
                )
                cur.execute(
                    """
                    INSERT INTO projects (id, council_id, slug, name, status)
                    VALUES (%s::uuid, %s::uuid, %s, %s, 'active')
                """,
                    (project_id, council_id, project_slug, args.project_name),
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
                        road_ver,
                        args.boundary_source_name,
                        road_key,
                        checksum_use,
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
                    (device_id, project_id, install_id),
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
    except psycopg.errors.UniqueViolation as e:
        return _die(
            "Database rejected the insert (unique constraint). This council slug or "
            "(project_id, stable_install_id) may already exist. Use new slugs/install id, "
            f"or clean up Neon. Detail: {e}"
        )
    except psycopg.Error as e:
        return _die(
            f"Database error while seeding (no changes committed): {e}. "
            f"Check migrations and that PostGIS ST_GeomFromGeoJSON accepts your boundary."
        )

    print("")
    print("========== PILOT SEED COMPLETE ==========")
    print(f"Council slug:     {council_slug}")
    print(f"Council name:     {council_name}")
    print(f"Project slug:     {project_slug}")
    print(f"Road-pack ver.:   {road_ver}")
    print(f"Storage key:      {road_key}")
    print(f"Stable install:   {install_id}")
    print(f"council_id:       {council_id}")
    print(f"project_id:       {project_id}")
    print(f"device_id:        {device_id}")
    print("")
    print("DEVICE_UPLOAD (paste once into Android Settings -> DEVICE_UPLOAD API key):")
    print(device_upload_secret)
    print("")
    print("COUNCIL_READ (GIS: Authorization: Bearer ... on manifest + layer URLs):")
    print(council_read_secret)
    print("")
    print("--------- Next commands (repo root, bash) ---------")
    print(f"  python backend/scripts/pilot_preflight.py --council-slug {council_slug}")
    print("  export PILOT_DEVICE_UPLOAD_KEY='(paste DEVICE_UPLOAD above)'")
    print("  export PILOT_COUNCIL_READ_KEY='(paste COUNCIL_READ above)'")
    print(
        f"  python backend/scripts/pilot_smoke_e2e.py --council-slug {council_slug}"
    )
    print(
        f"  python backend/scripts/pilot_smoke_e2e.py --council-slug {council_slug} --require-published"
    )
    print("")
    print("--------- Next commands (repo root, Windows PowerShell) ---------")
    print(f"  python backend\\scripts\\pilot_preflight.py --council-slug {council_slug}")
    print("  $env:PILOT_DEVICE_UPLOAD_KEY = \"(paste DEVICE_UPLOAD above)\"")
    print("  $env:PILOT_COUNCIL_READ_KEY = \"(paste COUNCIL_READ above)\"")
    print(
        f"  python backend\\scripts\\pilot_smoke_e2e.py --council-slug {council_slug}"
    )
    print(
        f"  python backend\\scripts\\pilot_smoke_e2e.py --council-slug {council_slug} --require-published"
    )
    print("")
    print(
        "  # After boundary is in Neon, build/upload pack and register checksum (or rely on build script):"
    )
    print(
        "  # See backend/roadpack-build/build_road_pack.py (expects COUNCIL_SLUG, PACK_VERSION, …)"
    )
    print(f"  # On-device path: files/road_packs/{council_slug}/{road_ver}/public-roads.geojson")
    print("==============================================")
    print("")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
