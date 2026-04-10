"""
Hosted processing worker — claims PENDING jobs, validates bundle, runs roughness_lab-derived analysis.

Idempotency:
- COMPLETED jobs are not selected (SKIP LOCKED on PENDING only).
- After the job row is committed to RUNNING, DELETE all *_hosted rows for that
  recording_session_id, then INSERT fresh derived rows (delete-and-rebuild per run).
  Never DELETE before RUNNING is committed.

Requires repo root on sys.path for `analysis.roughness_lab` (inserted below; CI sets PYTHONPATH too).
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

# Repo root: backend/processing -> parents[2] = roads — before libs that import analysis.*
_REPO_ROOT = Path(__file__).resolve().parents[2]
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))

import psycopg
from psycopg.types.json import Json

from libs.hosted_analysis import (
    PROCESSOR_METHOD_VERSION,
    anomalies_from_windows,
    anomaly_row_to_payload,
    run_roughness_on_extracted_root,
    window_row_to_payload,
)
from libs.manifest_validate import validate_manifest_session_uuid
from libs.supabase_storage import storage_download_bytes

PROCESSOR_VERSION = "0.2.0-hosted"


def load_env():
    p = Path(__file__).resolve().parents[1] / ".env.local"
    if p.is_file():
        from dotenv import load_dotenv

        load_dotenv(p)


def connect():
    dsn = os.environ.get("DATABASE_URL") or os.environ.get("DATABASE_URL_POOLED")
    if not dsn:
        print("DATABASE_URL not set — exit 0", file=sys.stderr)
        sys.exit(0)
    return psycopg.connect(dsn)


def _delete_hosted_for_session(cur, session_id: str) -> None:
    cur.execute(
        "DELETE FROM derived_window_features_hosted WHERE recording_session_id = %s::uuid",
        (session_id,),
    )
    cur.execute(
        "DELETE FROM anomaly_candidates_hosted WHERE recording_session_id = %s::uuid",
        (session_id,),
    )
    cur.execute(
        "DELETE FROM segment_consensus_hosted WHERE recording_session_id = %s::uuid",
        (session_id,),
    )


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _process_bundle_extracted(
    root: Path,
    council_id: str,
    project_id: str,
    recording_session_id: str,
    client_session_uuid: str,
    cur,
) -> tuple[int, int]:
    """Insert hosted rows; returns (derived_count, anomaly_count). Consensus skipped this slice."""
    uuid_from_file = client_session_uuid
    sj = root / "session.json"
    if sj.is_file():
        with open(sj, encoding="utf-8") as f:
            uuid_from_file = str(json.load(f).get("uuid", uuid_from_file))

    df = run_roughness_on_extracted_root(root, uuid_from_file)
    derived_n = 0
    ano_n = 0
    if not df.empty:
        for _, row in df.iterrows():
            payload = window_row_to_payload(
                row, council_id, project_id, recording_session_id, client_session_uuid
            )
            cur.execute(
                """
                INSERT INTO derived_window_features_hosted (
                  recording_session_id, council_id, project_id, payload_json
                ) VALUES (%s::uuid, %s::uuid, %s::uuid, %s)
                """,
                (recording_session_id, council_id, project_id, Json(payload)),
            )
            derived_n += 1

        ano_df = anomalies_from_windows(df)
        for _, row in ano_df.iterrows():
            payload = anomaly_row_to_payload(
                row, council_id, project_id, recording_session_id, client_session_uuid
            )
            cur.execute(
                """
                INSERT INTO anomaly_candidates_hosted (
                  recording_session_id, council_id, project_id, payload_json
                ) VALUES (%s::uuid, %s::uuid, %s::uuid, %s)
                """,
                (recording_session_id, council_id, project_id, Json(payload)),
            )
            ano_n += 1

    return derived_n, ano_n


def main():
    load_env()
    bucket = os.environ.get("SUPABASE_RAW_BUCKET", "roads-alpha-raw")
    max_jobs = int(os.environ.get("PROCESSING_MAX_JOBS", "3"))

    with connect() as conn:
        for _ in range(max_jobs):
            conn.autocommit = False
            job_id = None
            session_id = None
            storage_key = None
            artifact_checksum = None
            council_id = None
            project_id = None
            client_uuid = None

            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT pj.id, pj.recording_session_id, a.storage_key, a.checksum,
                           rs.council_id, rs.project_id, rs.client_session_uuid::text
                    FROM processing_jobs pj
                    JOIN recording_sessions rs ON rs.id = pj.recording_session_id
                    LEFT JOIN artifacts a ON a.id = COALESCE(rs.filtered_artifact_id, rs.raw_artifact_id)
                    WHERE pj.state = 'PENDING' AND a.storage_key IS NOT NULL
                    ORDER BY pj.created_at
                    LIMIT 1
                    FOR UPDATE OF pj SKIP LOCKED
                    """
                )
                row = cur.fetchone()
                if not row:
                    conn.commit()
                    break

                job_id = str(row[0])
                session_id = str(row[1])
                storage_key = row[2]
                artifact_checksum = row[3]
                council_id = str(row[4])
                project_id = str(row[5])
                client_uuid = str(row[6])

                cur.execute(
                    """
                    UPDATE processing_jobs
                    SET state = 'RUNNING', started_at = now(), processor_version = %s
                    WHERE id = %s::uuid
                    """,
                    (PROCESSOR_VERSION, job_id),
                )
                conn.commit()

            # RUNNING committed — safe to rebuild hosted rows
            tmp_extract: Path | None = None
            try:
                with conn.cursor() as cur_del:
                    _delete_hosted_for_session(cur_del, session_id)
                conn.commit()

                raw = storage_download_bytes(bucket=bucket, object_key=storage_key)
                if artifact_checksum and _sha256_bytes(raw).lower() != str(artifact_checksum).lower():
                    raise ValueError("bundle checksum mismatch")

                zf = zipfile.ZipFile(io.BytesIO(raw))
                if "manifest.json" not in zf.namelist():
                    raise ValueError("bundle missing manifest.json")
                manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
                if not isinstance(manifest, dict):
                    raise ValueError("invalid manifest")
                validate_manifest_session_uuid(manifest, client_uuid)

                tmp_extract = Path(tempfile.mkdtemp(prefix="olgx_proc_"))
                zf.extractall(tmp_extract)
                subs = [p for p in tmp_extract.iterdir() if p.is_dir()]
                if len(subs) == 1 and (subs[0] / "session.json").is_file():
                    root = subs[0]
                elif (tmp_extract / "session.json").is_file():
                    root = tmp_extract
                else:
                    raise FileNotFoundError("could not find session root in zip")

                with conn.cursor() as cur2:
                    derived_n, ano_n = _process_bundle_extracted(
                        root,
                        council_id,
                        project_id,
                        session_id,
                        client_uuid,
                        cur2,
                    )
                    summary = {
                        "worker": PROCESSOR_VERSION,
                        "methodVersion": PROCESSOR_METHOD_VERSION,
                        "artifactChecksumSha256": str(artifact_checksum) if artifact_checksum else None,
                        "derivedWindowFeaturesHosted": derived_n,
                        "anomalyCandidatesHosted": ano_n,
                        "segmentConsensusHosted": 0,
                        "note": "experimental hosted analysis — not IRI",
                    }
                    cur2.execute(
                        """
                        UPDATE processing_jobs SET state = 'COMPLETED', completed_at = now(),
                          last_error = NULL WHERE id = %s::uuid
                        """,
                        (job_id,),
                    )
                    cur2.execute(
                        """
                        UPDATE recording_sessions SET processing_state = 'COMPLETED',
                          processing_summary_json = %s::jsonb, updated_at = now()
                        WHERE id = %s::uuid
                        """,
                        (json.dumps(summary), session_id),
                    )
                conn.commit()
                print(f"COMPLETED job {job_id} derived={derived_n} anomalies={ano_n}")
            except Exception as e:
                conn.rollback()
                if tmp_extract and tmp_extract.is_dir():
                    shutil.rmtree(tmp_extract, ignore_errors=True)
                with conn.cursor() as cur3:
                    cur3.execute(
                        """
                        UPDATE processing_jobs SET state = 'FAILED', last_error = %s, completed_at = now()
                        WHERE id = %s::uuid
                        """,
                        (str(e)[:2000], job_id),
                    )
                conn.commit()
                print(f"FAILED job {job_id}: {e}", file=sys.stderr)
            else:
                if tmp_extract and tmp_extract.is_dir():
                    shutil.rmtree(tmp_extract, ignore_errors=True)


if __name__ == "__main__":
    main()
