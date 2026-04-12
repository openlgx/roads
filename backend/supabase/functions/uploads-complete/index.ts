import { extractBearer, verifyApiKeyByHash } from "../_shared/auth.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { errorResponse } from "../_shared/errors.ts";
import {
  requireRawBucket,
  requireSupabaseProjectUrl,
  requireSupabaseServiceKey,
} from "../_shared/env.ts";
import { jsonResponse } from "../_shared/json.ts";
import { createSql } from "../_shared/neon.ts";
import { verifyStorageObjectSize } from "../_shared/storage_object.ts";

type CompleteBody = {
  apiVersion: number;
  uploadJobId: string;
  objectKey: string;
  byteSize: number;
  contentChecksumSha256: string;
  clientSessionUuid?: string;
  artifactKind?: string;
};

type UploadJobRow = {
  id: string;
  recording_session_id: string;
  state: string;
  object_key: string | null;
  content_checksum_sha256: string | null;
  byte_size: string | null;
  artifact_kind: string | null;
  multipart_group_id: string | null;
  part_index: number | null;
  part_total: number | null;
  whole_file_checksum_sha256: string | null;
  whole_file_bytes: string | null;
};

function isUuid(s: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    .test(s);
}

async function tryFinalizeMultipart(
  sql: ReturnType<typeof createSql>,
  job: UploadJobRow,
  s: { id: string; project_id: string; council_id: string },
  kind: string,
  bucket: string,
): Promise<{ artifactId: string; processingJobId: string | null } | null> {
  const gid = job.multipart_group_id;
  const partTotal = job.part_total;
  if (!gid || partTotal == null || partTotal < 1) return null;

  const done = await sql<{ c: number }[]>`
    SELECT COUNT(*)::int AS c FROM upload_jobs
    WHERE multipart_group_id = ${gid}::uuid AND state = 'COMPLETED'`;
  if ((done[0]?.c ?? 0) < partTotal) return null;

  const parts = await sql<
    {
      object_key: string | null;
      byte_size: string | null;
      part_index: number | null;
      whole_file_checksum_sha256: string | null;
      whole_file_bytes: string | null;
    }[]
  >`
    SELECT object_key, byte_size::text, part_index,
           whole_file_checksum_sha256, whole_file_bytes::text
    FROM upload_jobs
    WHERE multipart_group_id = ${gid}::uuid AND state = 'COMPLETED'
    ORDER BY part_index ASC`;
  if (parts.length !== partTotal) return null;

  const keys: string[] = [];
  let sum = 0;
  let wholeChk: string | null = null;
  let wholeBytes: number | null = null;
  for (const p of parts) {
    if (!p.object_key || p.byte_size == null || p.part_index == null) {
      throw new Error("multipart part row incomplete");
    }
    keys.push(p.object_key);
    sum += Number(p.byte_size);
    const wch = (p.whole_file_checksum_sha256 ?? "").toLowerCase();
    const wb = p.whole_file_bytes != null ? Number(p.whole_file_bytes) : null;
    if (wholeChk == null) wholeChk = wch;
    else if (wholeChk !== wch) throw new Error("multipart whole checksum mismatch across parts");
    if (wholeBytes == null) wholeBytes = wb;
    else if (wholeBytes !== wb) throw new Error("multipart whole size mismatch across parts");
  }
  if (!wholeChk || wholeBytes == null || sum !== wholeBytes) {
    throw new Error("multipart part sizes do not sum to declared whole file size");
  }

  const existing = await sql<{ id: string }[]>`
    SELECT id FROM artifacts
    WHERE recording_session_id = ${s.id}::uuid
      AND artifact_kind = ${kind}
      AND part_storage_keys_json IS NOT NULL
    ORDER BY created_at DESC LIMIT 1`;
  if (existing[0]) {
    const pj = await sql<{ id: string }[]>`
      SELECT id FROM processing_jobs
      WHERE recording_session_id = ${s.id}::uuid
      ORDER BY created_at DESC LIMIT 1`;
    return { artifactId: existing[0].id, processingJobId: pj[0]?.id ?? null };
  }

  const keysJson = JSON.stringify(keys);
  const artIns = await sql<{ id: string }[]>`
    INSERT INTO artifacts (
      council_id, project_id, recording_session_id, artifact_kind,
      storage_bucket, storage_key, mime_type, byte_size, checksum, part_storage_keys_json
    ) VALUES (
      ${s.council_id}::uuid,
      ${s.project_id}::uuid,
      ${s.id}::uuid,
      ${kind},
      ${bucket},
      ${keys[0]!},
      'application/zip',
      ${wholeBytes},
      ${wholeChk},
      ${keysJson}::jsonb
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

  return { artifactId, processingJobId };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders() });
  }
  if (req.method !== "POST") {
    return errorResponse("method_not_allowed", "POST only", 405);
  }

  let sql: ReturnType<typeof createSql> | undefined;
  try {
    sql = createSql();
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
    if (
      body.clientSessionUuid !== undefined &&
      !isUuid(body.clientSessionUuid)
    ) {
      return errorResponse("bad_request", "Invalid clientSessionUuid", 400);
    }

    const jobs = await sql<UploadJobRow[]>`
      SELECT id, recording_session_id, state, object_key, content_checksum_sha256,
             byte_size::text, artifact_kind,
             multipart_group_id, part_index, part_total,
             whole_file_checksum_sha256, whole_file_bytes::text
      FROM upload_jobs WHERE id = ${body.uploadJobId}::uuid LIMIT 1`;

    const job = jobs[0];
    if (!job) return errorResponse("not_found", "upload job not found", 404);

    const kind = job.artifact_kind ?? "RAW_UPLOAD";
    const bucket = requireRawBucket();

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

    if (body.clientSessionUuid !== undefined) {
      const match = await sql<{ ok: boolean }[]>`
        SELECT (client_session_uuid = ${body.clientSessionUuid}::uuid) AS ok
        FROM recording_sessions WHERE id = ${s.id}::uuid LIMIT 1`;
      if (!match[0]?.ok) {
        return errorResponse("conflict", "clientSessionUuid does not match session", 409);
      }
    }

    // --- Multipart: job already COMPLETED (idempotent) or finalize retry ---
    if (job.state === "COMPLETED" && job.multipart_group_id) {
      const artExisting = await sql<{ id: string }[]>`
        SELECT id FROM artifacts
        WHERE recording_session_id = ${s.id}::uuid
          AND artifact_kind = ${kind}
          AND part_storage_keys_json IS NOT NULL
        ORDER BY created_at DESC LIMIT 1`;
      if (artExisting[0]) {
        const proc = await sql<{ id: string }[]>`
          SELECT id FROM processing_jobs
          WHERE recording_session_id = ${s.id}::uuid
          ORDER BY created_at DESC LIMIT 1`;
        return jsonResponse({
          uploadJobId: job.id,
          state: "COMPLETED",
          artifactId: artExisting[0].id,
          multipartPending: false,
          completedParts: job.part_total ?? 0,
          totalParts: job.part_total ?? 0,
          processingJobId: proc[0]?.id ?? null,
        });
      }
      const finalized = await tryFinalizeMultipart(sql, job, s, kind, bucket);
      if (finalized) {
        return jsonResponse({
          uploadJobId: job.id,
          state: "COMPLETED",
          artifactId: finalized.artifactId,
          multipartPending: false,
          completedParts: job.part_total ?? 0,
          totalParts: job.part_total ?? 0,
          processingJobId: finalized.processingJobId,
        });
      }
      const cnt = await sql<{ c: number }[]>`
        SELECT COUNT(*)::int AS c FROM upload_jobs
        WHERE multipart_group_id = ${job.multipart_group_id}::uuid AND state = 'COMPLETED'`;
      return jsonResponse({
        uploadJobId: job.id,
        state: "PART_UPLOADED",
        artifactId: null,
        multipartPending: true,
        completedParts: cnt[0]?.c ?? 0,
        totalParts: job.part_total ?? 0,
        processingJobId: null,
      });
    }

    // --- Single-blob: idempotent COMPLETED ---
    if (job.state === "COMPLETED" && !job.multipart_group_id) {
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
        multipartPending: false,
        completedParts: 1,
        totalParts: 1,
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

    if (
      body.artifactKind !== undefined &&
      (job.artifact_kind ?? "").toUpperCase() !==
        body.artifactKind.toUpperCase()
    ) {
      return errorResponse("conflict", "artifactKind does not match upload job", 409);
    }

    const supabaseUrl = requireSupabaseProjectUrl().replace(/\/$/, "");
    const serviceKey = requireSupabaseServiceKey();
    try {
      await verifyStorageObjectSize(
        supabaseUrl,
        serviceKey,
        bucket,
        body.objectKey,
        body.byteSize,
      );
    } catch (e) {
      const msg = e instanceof Error ? e.message : "storage verify failed";
      return errorResponse("precondition_failed", msg, 412);
    }

    await sql`
      UPDATE upload_jobs SET state = 'COMPLETED', last_error = null, updated_at = now()
      WHERE id = ${job.id}::uuid`;

    // --- Multipart: more parts pending ---
    if (job.multipart_group_id && job.part_total != null) {
      const done = await sql<{ c: number }[]>`
        SELECT COUNT(*)::int AS c FROM upload_jobs
        WHERE multipart_group_id = ${job.multipart_group_id}::uuid AND state = 'COMPLETED'`;
      const c = done[0]?.c ?? 0;
      if (c < job.part_total) {
        return jsonResponse({
          uploadJobId: job.id,
          state: "PART_UPLOADED",
          artifactId: null,
          multipartPending: true,
          completedParts: c,
          totalParts: job.part_total,
          processingJobId: null,
        });
      }
      const fin = await tryFinalizeMultipart(sql, job, s, kind, bucket);
      if (!fin) {
        return errorResponse("internal_error", "multipart finalize failed", 500);
      }
      return jsonResponse({
        uploadJobId: job.id,
        state: "COMPLETED",
        artifactId: fin.artifactId,
        multipartPending: false,
        completedParts: job.part_total,
        totalParts: job.part_total,
        processingJobId: fin.processingJobId,
      });
    }

    // --- Single blob ---
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
      multipartPending: false,
      completedParts: 1,
      totalParts: 1,
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
    await sql?.end({ timeout: 5 }).catch(() => {});
  }
});
