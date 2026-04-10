"""
Council publish pipeline — fail-closed: no authoritative LGA boundary => no published/* writes.

Alpha: writes deterministic empty GeoJSON FeatureCollections until hosted derived data is populated.
Extend: load features from derived_window_features_hosted / anomaly_candidates_hosted, clip with Shapely.
"""
from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

import psycopg

from libs.clip_geojson import (
    clip_line_to_boundary,
    clip_point_to_boundary,
    shapely_boundary_from_geojson,
)
from libs.paths import (
    published_anomalies_geojson_key,
    published_consensus_geojson_key,
    published_manifest_key,
    published_roughness_geojson_key,
)
from libs.supabase_storage import storage_upload

CONSENSUS_MIN_SESSIONS = 2
CONSENSUS_MIN_POINTS = 50


def load_env():
    env_file = Path(__file__).resolve().parents[1] / ".env.local"
    if env_file.is_file():
        from dotenv import load_dotenv

        load_dotenv(env_file)


def connect():
    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL not set — skip", file=sys.stderr)
        sys.exit(0)
    return psycopg.connect(dsn)


def dumped_sorted_json(obj) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"))


def _payload_as_dict(payload) -> dict:
    if isinstance(payload, dict):
        return payload
    if isinstance(payload, str):
        return json.loads(payload)
    return json.loads(json.dumps(payload))


def publish_council(cur, conn, council_id: str, council_slug: str, publisher_version: str) -> None:
    published_bucket = os.environ.get("SUPABASE_PUBLISHED_BUCKET", "roads-alpha-published")

    cur.execute(
        """
        SELECT ST_AsGeoJSON(geometry)::text
        FROM lga_boundaries WHERE council_id = %s::uuid AND geometry IS NOT NULL
        """,
        (council_id,),
    )
    row = cur.fetchone()
    geojson_txt = row[0] if row else None
    if not geojson_txt:
        cur.execute(
            """
            INSERT INTO published_layer_runs (
              id, council_id, state, publisher_version, summary_json, completed_at
            ) VALUES (%s::uuid, %s::uuid, 'FAILED', %s, %s::jsonb, now())
            """,
            (
                str(uuid.uuid4()),
                council_id,
                publisher_version,
                json.dumps(
                    {
                        "reason": "no_authoritative_lga_boundary",
                        "councilSlug": council_slug,
                    }
                ),
            ),
        )
        print(f"SKIP council {council_slug}: no boundary (fail-closed)")
        return

    boundary = shapely_boundary_from_geojson(geojson_txt)

    cur.execute(
        "SELECT id FROM projects WHERE council_id = %s::uuid LIMIT 1", (council_id,)
    )
    pr = cur.fetchone()
    if not pr:
        print(f"SKIP council {council_slug}: no project row")
        return
    project_id = str(pr[0])

    cur.execute(
        """
        INSERT INTO published_layer_runs (
          council_id, state, publisher_version, summary_json
        ) VALUES (%s::uuid, 'RUNNING', %s, '{}'::jsonb)
        RETURNING id
        """,
        (council_id, publisher_version),
    )
    run_uuid = str(cur.fetchone()[0])

    cur.execute(
        """
        SELECT payload_json, recording_session_id::text
        FROM derived_window_features_hosted
        WHERE council_id = %s::uuid AND project_id = %s::uuid
        ORDER BY recording_session_id,
          COALESCE((payload_json->>'windowStartMs')::float, 0::float)
        """,
        (council_id, project_id),
    )
    derived_rows = cur.fetchall()

    roughness_features: list[dict] = []
    consensus_points: list[tuple[float, float, float, str]] = []
    for i, (payload, rsid) in enumerate(derived_rows):
        pl = _payload_as_dict(payload)
        geom = pl.get("geometry") or {}
        cg = clip_point_to_boundary(geom, boundary)
        if not cg:
            continue
        props = {k: v for k, v in pl.items() if k != "geometry"}
        props.pop("_sortSession", None)
        props.pop("_sortT", None)
        sort_t = float(props.get("windowStartMs") or 0)
        roughness_features.append(
            {"type": "Feature", "id": i, "geometry": cg, "properties": props}
        )
        c = cg.get("coordinates")
        if isinstance(c, list) and len(c) >= 2:
            consensus_points.append((float(c[0]), float(c[1]), sort_t, rsid))

    cur.execute(
        """
        SELECT payload_json, recording_session_id::text
        FROM anomaly_candidates_hosted
        WHERE council_id = %s::uuid AND project_id = %s::uuid
        ORDER BY recording_session_id,
          COALESCE((payload_json->>'windowStartMs')::float, 0::float)
        """,
        (council_id, project_id),
    )
    ano_rows = cur.fetchall()
    anomaly_features: list[dict] = []
    for i, (payload, rsid) in enumerate(ano_rows):
        pl = _payload_as_dict(payload)
        geom = pl.get("geometry") or {}
        cg = clip_point_to_boundary(geom, boundary)
        if not cg:
            continue
        props = {k: v for k, v in pl.items() if k != "geometry"}
        props["recordingSessionId"] = rsid
        anomaly_features.append({"type": "Feature", "id": i, "geometry": cg, "properties": props})

    distinct_sessions = {p[3] for p in consensus_points}
    consensus_fc: dict = {"type": "FeatureCollection", "features": []}
    consensus_emitted = False
    if (
        len(distinct_sessions) >= CONSENSUS_MIN_SESSIONS
        and len(consensus_points) >= CONSENSUS_MIN_POINTS
    ):
        from shapely.geometry import LineString

        consensus_points.sort(key=lambda x: (x[3], x[2], x[0], x[1]))
        coords = [(p[0], p[1]) for p in consensus_points]
        line = LineString(coords)
        for seg_geom in clip_line_to_boundary(line, boundary):
            consensus_fc["features"].append(
                {
                    "type": "Feature",
                    "geometry": seg_geom,
                    "properties": {
                        "kind": "hosted_consensus_trace",
                        "sourcePoints": len(consensus_points),
                        "sessionsRepresented": len(distinct_sessions),
                    },
                }
            )
        consensus_emitted = len(consensus_fc["features"]) > 0

    roughness_fc = {"type": "FeatureCollection", "features": roughness_features}
    anomalies_fc = {"type": "FeatureCollection", "features": anomaly_features}

    rough_j = dumped_sorted_json(roughness_fc)
    ano_j = dumped_sorted_json(anomalies_fc)
    con_j = dumped_sorted_json(consensus_fc)

    layer_artifacts: dict = {
        "roughness": {
            "storageKey": published_roughness_geojson_key(council_slug),
            "mimeType": "application/geo+json",
            "schemaVersion": 1,
            "byteSize": len(rough_j.encode("utf-8")),
        },
        "anomalies": {
            "storageKey": published_anomalies_geojson_key(council_slug),
            "mimeType": "application/geo+json",
            "schemaVersion": 1,
            "byteSize": len(ano_j.encode("utf-8")),
        },
        "consensus": {
            "storageKey": published_consensus_geojson_key(council_slug),
            "mimeType": "application/geo+json",
            "schemaVersion": 1,
            "byteSize": len(con_j.encode("utf-8")),
            "omitted": not consensus_emitted,
            "note": (
                "Multi-session density gate not met — empty FeatureCollection for stable URLs."
                if not consensus_emitted
                else "Council-level clipped trace."
            ),
        },
    }

    manifest = {
        "manifestVersion": 2,
        "councilSlug": council_slug,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
        "publishRunId": run_uuid,
        "layerArtifacts": layer_artifacts,
        "sourceProcessingVersions": {
            "publisher": publisher_version,
            "note": "Experimental alpha — not IRI; LGA-clipped only.",
        },
        "consensusEmitted": consensus_emitted,
        "disclaimer": "Experimental council-facing layers — not IRI or formal road condition.",
        "refreshCadenceNote": (
            "GIS clients should refresh on their schedule; automated publish may run roughly every 12h "
            "— see docs/pilot-readiness.md."
        ),
    }
    man_j = dumped_sorted_json(manifest)

    uploads = [
        (published_roughness_geojson_key(council_slug), rough_j, "application/geo+json"),
        (published_anomalies_geojson_key(council_slug), ano_j, "application/geo+json"),
        (published_consensus_geojson_key(council_slug), con_j, "application/geo+json"),
        (published_manifest_key(council_slug), man_j, "application/json"),
    ]

    for object_key, body, ctype in uploads:
        storage_upload(
            bucket=published_bucket,
            object_key=object_key,
            data=body.encode("utf-8"),
            content_type=ctype,
            upsert=True,
        )

    artifact_specs = [
        ("PUBLISHED_ROUGHNESS", published_roughness_geojson_key(council_slug), rough_j, "ROUGHNESS"),
        ("PUBLISHED_ANOMALIES", published_anomalies_geojson_key(council_slug), ano_j, "ANOMALIES"),
        ("PUBLISHED_CONSENSUS", published_consensus_geojson_key(council_slug), con_j, "CONSENSUS"),
        ("MANIFEST", published_manifest_key(council_slug), man_j, "MANIFEST"),
    ]

    for kind, object_key, body, layer_kind in artifact_specs:
        ctype = "application/json" if kind == "MANIFEST" else "application/geo+json"
        cur.execute(
            """
            INSERT INTO artifacts (
              council_id, project_id, recording_session_id, artifact_kind,
              storage_bucket, storage_key, mime_type, byte_size, checksum, schema_version
            ) VALUES (
              %s::uuid, %s::uuid, NULL, %s, %s, %s, %s, %s, NULL, 1
            )
            RETURNING id
            """,
            (
                council_id,
                project_id,
                kind,
                published_bucket,
                object_key,
                ctype,
                len(body.encode("utf-8")),
            ),
        )
        aid = str(cur.fetchone()[0])
        cur.execute(
            """
            INSERT INTO published_layer_artifacts (
              published_layer_run_id, council_id, layer_kind, artifact_id
            ) VALUES (%s::uuid, %s::uuid, %s, %s::uuid)
            """,
            (run_uuid, council_id, layer_kind, aid),
        )

    cur.execute(
        """
        UPDATE published_layer_runs SET state = 'COMPLETED', completed_at = now(),
          summary_json = %s::jsonb WHERE id = %s::uuid
        """,
        (
            json.dumps(
                {
                    "councilSlug": council_slug,
                    "roughnessFeatures": len(roughness_features),
                    "anomalyFeatures": len(anomaly_features),
                    "consensusFeatureCount": len(consensus_fc["features"]),
                    "consensusEmitted": consensus_emitted,
                }
            ),
            run_uuid,
        ),
    )
    print(f"Published council {council_slug} run {run_uuid}")


def main():
    load_env()
    publisher_version = os.environ.get("PUBLISHER_VERSION", "0.1.0-alpha")
    with connect() as conn:
        conn.autocommit = False
        with conn.cursor() as cur:
            cur.execute("SELECT id, slug::text FROM councils ORDER BY slug")
            councils = cur.fetchall()
            if not councils:
                print("No councils in DB — nothing to publish")
                return
            for cid, slug in councils:
                try:
                    publish_council(cur, conn, str(cid), str(slug), publisher_version)
                    conn.commit()
                except Exception as e:
                    conn.rollback()
                    print(f"ERROR council {slug}: {e}", file=sys.stderr)
                    raise


if __name__ == "__main__":
    main()
