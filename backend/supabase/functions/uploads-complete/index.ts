import { extractBearer, verifyApiKeyByHash } from "../_shared/auth.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { errorResponse } from "../_shared/errors.ts";
import { requireEnv } from "../_shared/env.ts";
import { jsonResponse } from "../_shared/json.ts";
import { createSql } from "../_shared/neon.ts";

type CompleteBody = {
  apiVersion: number;
  uploadJobId: string;
  objectKey: string;
  byteSize: number;
  contentChecksumSha256: string;
};

function isUuid(s: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    .test(s);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders() });
  }
  if (req.method !== "POST") {
    return errorResponse("method_not_allowed", "POST only", 405);
  }

  const sql = createSql();
  try {
    const apiKey = extractBearer(req);
    if (!apiKey) {
      return errorResponse("unauthorized", "Missing API key", 401);
    }

    const keyRow = await verifyApiKeyByHash(sql, apiKey, "DEVICE_UPLOAD");
    if (!keyRow) {
      return errorResponse("unauthorized", "Invalid API key", 401);
    }

    let body: CompleteBody;
    try {
      body = await req.json();
    } catch {
      return errorResponse("bad_request", "Invalid JSON body", 400);
    }

    if (body.apiVersion !== 1) {
      return errorResponse("bad_request", "apiVersion must be 1", 400);
    }
    if (!isUuid(body.uploadJobId)) {
      return errorResponse("bad_request", "Invalid uploadJobId", 400);
    }
    if (!/^[a-f0-9]{64}$/i.test(body.contentChecksumSha256)) {
      return errorResponse("bad_request", "Invalid checksum", 400);
    }

    const jobs = await sql<
      {
        id: string;
        recording_session_id: string;
        state: string;
        object_key: string | null;
        content_checksum_sha256: string | null;
        byte_size: string | null;
        artifact_kind: string | null;
      }[]
    >`
      SELECT id, recording_session_id, state, object_key, content_checksum_sha256,
             byte_size::text, artifact_kind
      FROM upload_jobs WHERE id = ${body.uploadJobId}::uuid LIMIT 1`;

    const job = jobs[0];
    if (!job) return errorResponse("not_found", "upload job not found", 404);

    if (job.state === "COMPLETED") {
      const existingArt = await sql<{ id: string }[]>`
        SELECT a.id FROM artifacts a
        JOIN recording_sessions rs ON (
          (rs.raw_artifact_id = a.id OR rs.filtered_artifact_id = a.id)
          AND rs.id = ${job.recording_session_id}::uuid
        )
        WHERE a.storage_key = ${body.objectKey}
        LIMIT 1`;
      const proc = await sql<{ id: string }[]>`
        SELECT id FROM processing_jobs
        WHERE recording_session_id = ${job.recording_session_id}::uuid
        ORDER BY created_at DESC LIMIT 1`;
      return jsonResponse({
        uploadJobId: job.id,
        state: "COMPLETED",
        artifactId: existingArt[0]?.id ?? null,
        processingJobId: proc[0]?.id ?? null,
      });
    }

    if (
      job.object_key !== body.objectKey ||
      Number(job.byte_size) !== body.byteSize ||
      (job.content_checksum_sha256 ?? "").toLowerCase() !==
        body.contentChecksumSha256.toLowerCase()
    ) {
      return errorResponse("conflict", "Metadata does not match upload job", 409);
    }

    const sess = await sql<
      {
        id: string;
        project_id: string;
        council_id: string;
      }[]
    >`
      SELECT id, project_id, council_id FROM recording_sessions
      WHERE id = ${job.recording_session_id}::uuid LIMIT 1`;
    const s = sess[0];
    if (!s) return errorResponse("not_found", "recording session missing", 404);

    if (keyRow.project_id && keyRow.project_id !== s.project_id) {
      return errorResponse("forbidden", "Key not scoped to this project", 403);
    }

    const bucket = requireEnv("SUPABASE_RAW_BUCKET");
    const kind = job.artifact_kind ?? "RAW_UPLOAD";

    const artIns = await sql<{ id: string }[]>`
      INSERT INTO artifacts (
        council_id, project_id, recording_session_id, artifact_kind,
        storage_bucket, storage_key, mime_type, byte_size, checksum
      ) VALUES (
        ${s.council_id}::uuid,
        ${s.project_id}::uuid,
        ${s.id}::uuid,
        ${kind},
        ${bucket},
        ${body.objectKey},
        'application/zip',
        ${body.byteSize},
        ${body.contentChecksumSha256.toLowerCase()}
      )
      RETURNING id`;
    const artifactId = artIns[0].id;

    if (kind === "FILTERED_UPLOAD") {
      await sql`
        UPDATE recording_sessions
        SET filtered_artifact_id = ${artifactId}::uuid,
            upload_state = 'COMPLETE',
            updated_at = now()
        WHERE id = ${s.id}::uuid`;
    } else {
      await sql`
        UPDATE recording_sessions
        SET raw_artifact_id = ${artifactId}::uuid,
            upload_state = 'PARTIAL',
            updated_at = now()
        WHERE id = ${s.id}::uuid`;
    }

    await sql`
      UPDATE upload_jobs SET state = 'COMPLETED', last_error = null, updated_at = now()
      WHERE id = ${job.id}::uuid`;

    const pending = await sql<{ id: string }[]>`
      SELECT id FROM processing_jobs
      WHERE recording_session_id = ${s.id}::uuid
        AND state IN ('PENDING', 'RUNNING')
      LIMIT 1`;
    let processingJobId: string | null = pending[0]?.id ?? null;
    if (!processingJobId) {
      const existsDone = await sql<{ c: number }[]>`
        SELECT COUNT(*)::int AS c FROM processing_jobs
        WHERE recording_session_id = ${s.id}::uuid AND state = 'COMPLETED'`;
      if ((existsDone[0]?.c ?? 0) === 0) {
        const pj = await sql<{ id: string }[]>`
          INSERT INTO processing_jobs (
            recording_session_id, state, processor_version
          ) VALUES (
            ${s.id}::uuid,
            'PENDING',
            '0.1.0-alpha'
          )
          RETURNING id`;
        processingJobId = pj[0].id;
        await sql`
          UPDATE recording_sessions
          SET processing_state = 'QUEUED', updated_at = now()
          WHERE id = ${s.id}::uuid`;
      }
    }

    return jsonResponse({
      uploadJobId: job.id,
      state: "COMPLETED",
      artifactId,
      processingJobId,
    });
  } catch (e) {
    console.error(e);
    return errorResponse(
      "internal_error",
      e instanceof Error ? e.message : "internal error",
      500,
    );
  } finally {
    await sql.end({ timeout: 5 });
  }
});
