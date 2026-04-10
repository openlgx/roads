"""
Hosted processing worker — claims PENDING jobs, validates bundle in Storage, unpack stub, marks state.

Idempotent: COMPLETED jobs are not selected.
"""
from __future__ import annotations

import io
import json
import os
import sys
import zipfile
from pathlib import Path

import psycopg

from libs.supabase_storage import storage_download_bytes


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


def main():
    load_env()
    bucket = os.environ.get("SUPABASE_RAW_BUCKET", "roads-alpha-raw")
    max_jobs = int(os.environ.get("PROCESSING_MAX_JOBS", "3"))

    with connect() as conn:
        for _ in range(max_jobs):
            conn.autocommit = False
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT pj.id, pj.recording_session_id, a.storage_key
                    FROM processing_jobs pj
                    JOIN recording_sessions rs ON rs.id = pj.recording_session_id
                    LEFT JOIN artifacts a ON a.id = rs.raw_artifact_id
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
                job_id, session_id, storage_key = row[0], row[1], row[2]
                try:
                    cur.execute(
                        """
                        UPDATE processing_jobs SET state = 'RUNNING', started_at = now() WHERE id = %s::uuid
                        """,
                        (str(job_id),),
                    )
                    conn.commit()

                    raw = storage_download_bytes(bucket=bucket, object_key=storage_key)
                    zf = zipfile.ZipFile(io.BytesIO(raw))
                    if "manifest.json" not in zf.namelist():
                        raise ValueError("bundle missing manifest.json")
                    manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
                    if not isinstance(manifest, dict):
                        raise ValueError("invalid manifest")

                    with conn.cursor() as cur2:
                        cur2.execute(
                            """
                            UPDATE processing_jobs SET state = 'COMPLETED', completed_at = now(),
                              last_error = NULL WHERE id = %s::uuid
                            """,
                            (str(job_id),),
                        )
                        cur2.execute(
                            """
                            UPDATE recording_sessions SET processing_state = 'COMPLETED',
                              processing_summary_json = %s::jsonb, updated_at = now()
                            WHERE id = %s::uuid
                            """,
                            (
                                json.dumps(
                                    {"worker": "0.1.0-alpha", "note": "stub — extend with analysis/"}
                                ),
                                str(session_id),
                            ),
                        )
                    conn.commit()
                    print(f"COMPLETED job {job_id}")
                except Exception as e:
                    conn.rollback()
                    with conn.cursor() as cur3:
                        cur3.execute(
                            """
                            UPDATE processing_jobs SET state = 'FAILED', last_error = %s, completed_at = now()
                            WHERE id = %s::uuid
                            """,
                            (str(e)[:2000], str(job_id)),
                        )
                    conn.commit()
                    print(f"FAILED job {job_id}: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
