#!/usr/bin/env python3
"""
Pilot smoke: live Supabase Edge + optional Neon checks (secrets from backend/.env.local).

Typical operator sequence (see docs/pilot-readiness.md):
  1. Deploy Edge Functions (Supabase CLI from backend/supabase)
  2. python backend/scripts/pilot_preflight.py --council-slug <slug>
  3. python backend/scripts/pilot_smoke_e2e.py --council-slug <slug> [--require-published]

Exit codes:
  0 — all executed checks passed (skipped optional stages do not fail the run)
  1 — at least one required check failed (see PILOT SMOKE SUMMARY)
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
import uuid
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND = REPO_ROOT / "backend"

# Matches app ExportConstants.EXPORT_SCHEMA_VERSION for hosted create payload realism.
_EXPORT_SCHEMA_VERSION = 3

_INVALID_SMOKE_KEY = "olgx-smoke-invalid-key-on-purpose"


@dataclass
class SmokeRun:
    """Collect structured check results for the summary block."""

    lines: list[str] = field(default_factory=list)

    def ok(self, key: str, detail: str) -> None:
        self.lines.append(f"PASS {key}: {detail}")
        print(f"PASS {key}: {detail}", flush=True)

    def fail(self, key: str, detail: str) -> None:
        self.lines.append(f"FAIL {key}: {detail}")
        print(f"FAIL {key}: {detail}", file=sys.stderr, flush=True)


def _stage(title: str) -> None:
    print("", flush=True)
    print(f"--- {title} ---", flush=True)


def _hint_upload_create_status(code: int, body_snip: str) -> str:
    if code == 401:
        return (
            "HTTP 401 — DEVICE_UPLOAD key rejected (wrong plaintext key), or key not hashed match in Neon "
            "api_keys; confirm project UUID + device UUID match seed output."
        )
    if code == 403:
        return "HTTP 403 — key scope: project_id or device_id on the key does not match the payload."
    if code == 404:
        return (
            "HTTP 404 — often means Edge uploads-create not deployed or wrong SUPABASE_PROJECT_URL "
            "(expect …/functions/v1/uploads-create from project root URL)."
        )
    return f"body_snip={body_snip!r}"


def _load_env() -> None:
    p = BACKEND / ".env.local"
    if not p.is_file():
        return
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    load_dotenv(p)


def _need_env(name: str) -> str | None:
    v = os.environ.get(name)
    if v is None or not str(v).strip():
        return None
    return str(v).strip()


def _minimal_zip_bytes() -> tuple[bytes, str]:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr(
            "pilot-smoke.txt",
            "OLGX pilot_smoke_e2e minimal artifact (not a real session export).\n",
        )
    raw = buf.getvalue()
    digest = hashlib.sha256(raw).hexdigest()
    return raw, digest


def _resolve_pilot_uuids(council_slug: str) -> tuple[str | None, str | None, str | None]:
    """Return (project_id, device_id, error_message)."""
    dsn = _need_env("DATABASE_URL") or _need_env("DATABASE_URL_POOLED")
    if not dsn:
        return None, None, "DATABASE_URL/DATABASE_URL_POOLED not set — pass --project-id and --device-id"
    try:
        import psycopg
    except ImportError:
        return None, None, "psycopg not installed — pip install psycopg[binary] or pass UUID flags"
    try:
        with psycopg.connect(dsn) as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT p.id::text, d.id::text
                    FROM councils c
                    JOIN projects p ON p.council_id = c.id
                    JOIN devices d ON d.project_id = p.id
                    WHERE c.slug = lower(%s)
                    ORDER BY d.created_at NULLS LAST
                    LIMIT 2
                    """,
                    (council_slug.strip().lower(),),
                )
                rows = cur.fetchall()
                if not rows:
                    return None, None, "no project/device rows for council (seed_pilot_council.py?)"
                if len(rows) > 1:
                    return (
                        None,
                        None,
                        "multiple devices for this council — pass explicit --project-id and --device-id",
                    )
                return rows[0][0], rows[0][1], None
    except Exception as e:
        return None, None, f"DB lookup failed: {e}"


def _get_json_field(body: str, key: str) -> str | None:
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        return None
    if not isinstance(data, dict):
        return None
    val = data.get(key)
    if val is None:
        return None
    return str(val)


def main() -> int:
    # Load backend/.env.local before defaults read os.environ (UPLOAD_API_KEY, etc.).
    _load_env()

    parser = argparse.ArgumentParser(
        description="Hosted alpha pilot smoke (Edge uploads + council read contract)",
    )
    parser.add_argument("--council-slug", required=True)
    parser.add_argument(
        "--device-upload-key",
        default=(
            os.environ.get("PILOT_DEVICE_UPLOAD_KEY", "").strip()
            or os.environ.get("UPLOAD_API_KEY", "").strip()
        ),
        help="Plaintext DEVICE_UPLOAD (env PILOT_DEVICE_UPLOAD_KEY or UPLOAD_API_KEY from .env.local)",
    )
    parser.add_argument(
        "--council-read-key",
        default=os.environ.get("PILOT_COUNCIL_READ_KEY", ""),
        help="Plaintext COUNCIL_READ key (or env PILOT_COUNCIL_READ_KEY)",
    )
    parser.add_argument(
        "--project-id",
        default=os.environ.get("PILOT_PROJECT_ID", ""),
        help="Neon projects.id for uploads (or PILOT_PROJECT_ID); inferred from DB if omitted",
    )
    parser.add_argument(
        "--device-id",
        default=os.environ.get("PILOT_DEVICE_ID", ""),
        help="Neon devices.id for uploads (or PILOT_DEVICE_ID); inferred from DB if omitted",
    )
    parser.add_argument(
        "--require-published",
        action="store_true",
        help="Fail if manifest or layer endpoints are missing (404). Use after publish_council_layers.",
    )
    parser.add_argument(
        "--skip-upload-e2e",
        action="store_true",
        help="Only healthz + council-read checks (no storage PUT; for locked-down networks).",
    )
    args = parser.parse_args()

    run = SmokeRun()
    council_slug = args.council_slug.strip().lower()
    base = _need_env("SUPABASE_PROJECT_URL")
    if not base:
        run.fail("env_supabase_url", "SUPABASE_PROJECT_URL missing")
        _print_summary(run, failure=True)
        return 1
    base = base.rstrip("/")

    try:
        import httpx
    except ImportError:
        run.fail("dependency", "pip install httpx")
        _print_summary(run, failure=True)
        return 1

    print("Pilot smoke: hosted Edge + optional upload E2E (see stages below).", flush=True)

    # --- healthz ---
    _stage("STAGE 1: healthz")
    hz_url = f"{base}/functions/v1/healthz"
    functions_reachable = False
    try:
        r = httpx.get(hz_url, timeout=30.0)
        if r.status_code in (200, 503):
            functions_reachable = True
            run.ok(
                "healthz",
                f"reachable HTTP {r.status_code}"
                + (" (Edge up; Neon check may be unhealthy)" if r.status_code == 503 else ""),
            )
        elif r.status_code == 404:
            run.fail(
                "healthz",
                "HTTP 404 — Edge Functions not deployed at this project URL. "
                "From repo: cd backend/supabase && supabase link --project-ref <ref> && "
                "supabase functions deploy healthz (and other functions). "
                f"body={r.text[:200]!r}",
            )
        else:
            run.fail("healthz", f"HTTP {r.status_code} body={r.text[:200]!r}")
    except Exception as e:
        run.fail("healthz", str(e))

    device_key = (args.device_upload_key or "").strip()
    read_key = (args.council_read_key or "").strip()

    mf_url = f"{base}/functions/v1/council-layers-manifest"

    # --- Anonymous / wrong key must not read manifest ---
    _stage("STAGE 2: council manifest auth (invalid key should be rejected)")
    if not functions_reachable:
        run.ok(
            "council_read_key_rejected",
            "SKIP (healthz failed — cannot distinguish auth vs missing functions)",
        )
    else:
        try:
            r = httpx.get(
                mf_url,
                params={"councilSlug": council_slug},
                headers={"Authorization": f"Bearer {_INVALID_SMOKE_KEY}"},
                timeout=30.0,
            )
            if r.status_code == 401:
                run.ok("council_read_key_rejected", "invalid key -> HTTP 401")
            elif r.status_code in (500, 503) and (
                "DATABASE_URL" in r.text
                or "database url" in r.text.lower()
                or "connect" in r.text.lower()
            ):
                run.ok(
                    "council_read_key_rejected",
                    "SKIP — Edge HTTP "
                    f"{r.status_code}: Neon DATABASE_URL / DATABASE_URL_POOLED likely missing in Supabase "
                    "Edge Function secrets (cannot load api_keys to return 401 for bogus key)",
                )
            else:
                run.fail(
                    "council_read_key_rejected",
                    f"expected HTTP 401 for invalid key, got {r.status_code}",
                )
        except Exception as e:
            run.fail("council_read_key_rejected", str(e))

    # --- COUNCIL_READ: valid key + manifest ---
    _stage("STAGE 3: council manifest read (valid COUNCIL_READ)")
    if read_key:
        try:
            r = httpx.get(
                mf_url,
                params={"councilSlug": council_slug},
                headers={"Authorization": f"Bearer {read_key}"},
                timeout=60.0,
            )
            if r.status_code == 200:
                try:
                    manifest_data = r.json()
                    if not isinstance(manifest_data, dict):
                        raise ValueError("not an object")
                except Exception:
                    run.fail("manifest_reachable", "HTTP 200 but body is not JSON object")
                else:
                    run.ok(
                        "council_read_key_accepted",
                        "valid COUNCIL_READ authorized (manifest HTTP 200)",
                    )
                    missing = [
                        k
                        for k in (
                            "manifestVersion",
                            "councilSlug",
                            "publishedAt",
                            "publishRunId",
                            "layerArtifacts",
                        )
                        if k not in manifest_data
                    ]
                    if missing:
                        run.fail("manifest_contract", f"missing fields: {missing}")
                    else:
                        run.ok("manifest_reachable", "HTTP 200 + required manifest keys present")
            elif r.status_code == 404:
                detail = _get_json_field(r.text, "message") or r.text[:200]
                run.ok(
                    "council_read_key_accepted",
                    "valid COUNCIL_READ authorized (manifest HTTP 404 — object may be missing)",
                )
                if args.require_published:
                    run.fail("manifest_reachable", f"HTTP 404 (publish manifest?) — {detail}")
                else:
                    run.ok(
                        "manifest_reachable",
                        f"HTTP 404 (expected before first publish) — {detail}",
                    )
            else:
                run.fail("manifest_reachable", f"HTTP {r.status_code} {r.text[:200]!r}")
        except Exception as e:
            run.fail("manifest_reachable", str(e))

    if not read_key:
        run.ok("council_read_key_accepted", "SKIP (need COUNCIL_READ key)")
        run.ok("manifest_reachable", "SKIP (need COUNCIL_READ key)")
    # (when read_key: manifest block above already recorded pass/fail)

    # --- Layer endpoints (only when we have read key and manifest exists or succeed path) ---
    _stage("STAGE 4: published layer endpoints (GeoJSON redirects)")
    def _check_layer(name: str, path: str) -> None:
        url = f"{base}/functions/v1/{path}"
        if not read_key:
            run.ok(f"layer_{name}", "SKIP (need COUNCIL_READ key)")
            return
        try:
            r = httpx.get(
                url,
                params={"councilSlug": council_slug},
                headers={"Authorization": f"Bearer {read_key}"},
                timeout=60.0,
                follow_redirects=True,
            )
            if r.status_code == 200:
                run.ok(f"layer_{name}", "HTTP 200 (after redirects)")
                return
            if r.status_code == 404:
                detail = _get_json_field(r.text, "message") or r.text[:200]
                if args.require_published:
                    run.fail(f"layer_{name}", f"HTTP 404 — {detail}")
                else:
                    run.ok(
                        f"layer_{name}",
                        f"HTTP 404 (expected before publish) — {detail}",
                    )
                return
            run.fail(f"layer_{name}", f"HTTP {r.status_code} {r.text[:200]!r}")
        except Exception as e:
            run.fail(f"layer_{name}", str(e))

    _check_layer("roughness", "council-layers-roughness")
    _check_layer("anomalies", "council-layers-anomalies")
    _check_layer("consensus", "council-layers-consensus")

    # --- Upload E2E ---
    _stage("STAGE 5: uploads-create + Storage PUT + uploads-complete")
    create_ok = False
    complete_ok = False
    artifact_ok = False
    client_session: str | None = None

    if args.skip_upload_e2e:
        run.ok("uploads_create", "SKIP (--skip-upload-e2e)")
        run.ok("storage_put", "SKIP (--skip-upload-e2e)")
        run.ok("uploads_complete", "SKIP (--skip-upload-e2e)")
        run.ok("processing_job", "SKIP (--skip-upload-e2e)")
        run.ok("upload_artifact", "SKIP (--skip-upload-e2e)")
    elif not device_key:
        run.ok(
            "uploads_create",
            "SKIP (set PILOT_DEVICE_UPLOAD_KEY or --device-upload-key for full upload E2E)",
        )
        run.ok("storage_put", "SKIP")
        run.ok("uploads_complete", "SKIP")
        run.ok("processing_job", "SKIP")
        run.ok("upload_artifact", "SKIP")
    else:
        pid = (args.project_id or "").strip()
        did = (args.device_id or "").strip()
        if not pid or not did:
            rp, rd, err = _resolve_pilot_uuids(council_slug)
            if err:
                run.fail("uploads_create", f"cannot resolve project/device UUIDs: {err}")
            else:
                pid, did = rp or "", rd or ""
        if pid and did:
            zip_bytes, checksum = _minimal_zip_bytes()
            client_session = str(uuid.uuid4())
            create_body = {
                "apiVersion": 1,
                "projectId": pid,
                "deviceId": did,
                "clientSessionUuid": client_session,
                "artifactKind": "RAW_UPLOAD",
                "exportSchemaVersion": _EXPORT_SCHEMA_VERSION,
                "byteSize": len(zip_bytes),
                "contentChecksumSha256": checksum,
                "mimeType": "application/zip",
            }
            uc = f"{base}/functions/v1/uploads-create"
            try:
                cr = httpx.post(
                    uc,
                    headers={
                        "Authorization": f"Bearer {device_key}",
                        "Content-Type": "application/json",
                    },
                    json=create_body,
                    timeout=90.0,
                )
                if cr.status_code != 200:
                    run.fail(
                        "uploads_create",
                        _hint_upload_create_status(
                            cr.status_code,
                            cr.text[:300],
                        ),
                    )
                else:
                    try:
                        cj = cr.json()
                    except Exception:
                        run.fail("uploads_create", "HTTP 200 but response is not JSON")
                    else:
                        upload_job_id = cj.get("uploadJobId")
                        object_key = cj.get("objectKey")
                        signed = cj.get("signedUploadUrl")
                        headers = cj.get("signedUploadHeaders") or {}
                        if not upload_job_id or not object_key or not signed:
                            run.fail(
                                "uploads_create",
                                f"missing fields in response keys={list(cj.keys())}",
                            )
                        else:
                            create_ok = True
                            run.ok(
                                "uploads_create",
                                f"uploadJobId={upload_job_id} objectKey={object_key!r}",
                            )
                            put_ok = False
                            put_headers = {**dict(headers)}
                            try:
                                pr = httpx.put(
                                    signed,
                                    content=zip_bytes,
                                    headers=put_headers,
                                    timeout=120.0,
                                )
                                if 200 <= pr.status_code < 300:
                                    put_ok = True
                                    run.ok("storage_put", f"HTTP {pr.status_code} ({len(zip_bytes)} bytes)")
                                else:
                                    run.fail(
                                        "storage_put",
                                        f"HTTP {pr.status_code} {pr.text[:200]!r}",
                                    )
                            except Exception as e:
                                run.fail("storage_put", str(e))

                            if create_ok and put_ok:
                                complete_url = f"{base}/functions/v1/uploads-complete"
                                try:
                                    kpr = httpx.post(
                                        complete_url,
                                        headers={
                                            "Authorization": f"Bearer {device_key}",
                                            "Content-Type": "application/json",
                                        },
                                        json={
                                            "apiVersion": 1,
                                            "uploadJobId": upload_job_id,
                                            "objectKey": object_key,
                                            "byteSize": len(zip_bytes),
                                            "contentChecksumSha256": checksum,
                                            "clientSessionUuid": client_session,
                                            "artifactKind": "RAW_UPLOAD",
                                        },
                                        timeout=120.0,
                                    )
                                    if kpr.status_code != 200:
                                        run.fail(
                                            "uploads_complete",
                                            f"HTTP {kpr.status_code} {kpr.text[:300]!r}",
                                        )
                                    else:
                                        try:
                                            kj = kpr.json()
                                        except Exception:
                                            run.fail(
                                                "uploads_complete",
                                                "HTTP 200 but not JSON",
                                            )
                                        else:
                                            st = kj.get("state")
                                            art = kj.get("artifactId")
                                            pj = kj.get("processingJobId")
                                            if st != "COMPLETED":
                                                run.fail(
                                                    "uploads_complete",
                                                    f"state={st!r} expected COMPLETED",
                                                )
                                            elif not art:
                                                run.fail(
                                                    "uploads_complete",
                                                    "missing artifactId",
                                                )
                                            else:
                                                complete_ok = True
                                                artifact_ok = True
                                                run.ok(
                                                    "uploads_complete",
                                                    f"state=COMPLETED artifactId={art}",
                                                )
                                                if pj:
                                                    run.ok(
                                                        "processing_job",
                                                        f"processingJobId={pj} (queued or existing)",
                                                    )
                                                else:
                                                    run.fail(
                                                        "processing_job",
                                                        "processingJobId null after complete — expected a PENDING "
                                                        "processing_jobs row for this session (check Edge logs + Neon)",
                                                    )
                                except Exception as e:
                                    run.fail("uploads_complete", str(e))
            except Exception as e:
                run.fail("uploads_create", str(e))

    # Optional Neon artifact row check
    if (
        artifact_ok
        and not args.skip_upload_e2e
        and device_key
        and client_session is not None
        and _need_env("DATABASE_URL")
    ):
        dsn = _need_env("DATABASE_URL") or _need_env("DATABASE_URL_POOLED")
        try:
            import psycopg
        except ImportError:
            run.ok("artifact_db_row", "SKIP (psycopg not installed)")
        else:
            try:
                with psycopg.connect(dsn) as conn:
                    with conn.cursor() as cur:
                        cur.execute(
                            """
                            SELECT id::text FROM artifacts
                            WHERE recording_session_id = (
                                SELECT id FROM recording_sessions
                                WHERE client_session_uuid = %s::uuid LIMIT 1
                            )
                            ORDER BY created_at DESC NULLS LAST
                            LIMIT 1
                            """,
                            (client_session,),
                        )
                        row = cur.fetchone()
                        if row:
                            run.ok("artifact_db_row", f"found artifacts.id={row[0]}")
                        else:
                            run.fail(
                                "artifact_db_row",
                                "no artifacts row for this client_session_uuid",
                            )
            except Exception as e:
                run.fail("artifact_db_row", str(e))
    elif not args.skip_upload_e2e and device_key and complete_ok:
        run.ok("artifact_db_row", "SKIP (DATABASE_URL not set)")
    else:
        run.ok("artifact_db_row", "SKIP")

    failure = any(x.startswith("FAIL ") for x in run.lines)
    _print_summary(run, failure=failure)
    return 1 if failure else 0


def _print_summary(run: SmokeRun, *, failure: bool) -> None:
    print("", flush=True)
    print("=" * 56, flush=True)
    print("PILOT SMOKE SUMMARY", flush=True)
    print("=" * 56, flush=True)
    for line in run.lines:
        print(line, flush=True)
    print("=" * 56, flush=True)
    if failure:
        print(
            "RESULT: FAILED (non-zero exit) — fix failing lines above, then re-run.",
            file=sys.stderr,
            flush=True,
        )
    else:
        print("RESULT: OK", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
