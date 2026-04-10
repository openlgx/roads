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

from libs.paths import (
    published_anomalies_geojson_key,
    published_consensus_geojson_key,
    published_manifest_key,
    published_roughness_geojson_key,
)
from libs.supabase_storage import storage_upload


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


def publish_council(cur, conn, council_id: str, council_slug: str, publisher_version: str) -> None:
    published_bucket = os.environ.get("SUPABASE_PUBLISHED_BUCKET", "roads-alpha-published")

    cur.execute(
        """
        SELECT id, geometry IS NOT NULL AS has_geom
        FROM lga_boundaries WHERE council_id = %s::uuid
        """,
        (council_id,),
    )
    row = cur.fetchone()
    if not row or not row[1]:
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

    roughness_fc = {"type": "FeatureCollection", "features": []}
    anomalies_fc = {"type": "FeatureCollection", "features": []}
    consensus_fc = {"type": "FeatureCollection", "features": []}

    rough_j = dumped_sorted_json(roughness_fc)
    ano_j = dumped_sorted_json(anomalies_fc)
    con_j = dumped_sorted_json(consensus_fc)

    manifest = {
        "manifestVersion": 1,
        "councilSlug": council_slug,
        "publishedAt": datetime.now(timezone.utc).isoformat(),
        "publishRunId": run_uuid,
        "layerArtifacts": {
            "roughness": {
                "storageKey": published_roughness_geojson_key(council_slug),
                "mimeType": "application/geo+json",
                "schemaVersion": 1,
            },
            "anomalies": {
                "storageKey": published_anomalies_geojson_key(council_slug),
                "mimeType": "application/geo+json",
                "schemaVersion": 1,
            },
            "consensus": {
                "storageKey": published_consensus_geojson_key(council_slug),
                "mimeType": "application/geo+json",
                "schemaVersion": 1,
            },
        },
        "sourceProcessingVersions": {
            "publisher": publisher_version,
            "note": "Experimental alpha — not IRI; geometries clipped to LGA when features exist.",
        },
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
        (json.dumps({"councilSlug": council_slug, "layerFiles": 4}), run_uuid),
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
