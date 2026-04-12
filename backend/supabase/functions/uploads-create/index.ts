import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.8";
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
import { filteredSessionZipKey, rawSessionZipKey } from "../_shared/paths.ts";

type CreateBody = {
  apiVersion: number;
  projectId: string;
  deviceId: string;
  clientSessionUuid: string;
  artifactKind: "RAW_UPLOAD" | "FILTERED_UPLOAD";
  exportSchemaVersion: number;
  byteSize: number;
  contentChecksumSha256: string;
  mimeType: string;
  startedAtEpochMs?: number;
  /**
   * When set, this request is one part of a logical ZIP. Each part must be ≤ CHUNK_MAX_BYTES
   * (under Supabase Free Storage 50 MiB). Object keys are `…/session.zip.part0000`, etc.
   */
  multipart?: {
    groupId: string;
    partIndex: number;
    partTotal: number;
    wholeFileBytes: number;
    wholeFileChecksumSha256: string;
  };
};

/** Max logical ZIP (sum of parts). Keep in sync with docs/api-contract.md. */
const MAX_UPLOAD_BYTES = 1024 * 1024 * 1024; // 1 GiB
/** Per-part max: stay under Free tier Storage global limit (50 MiB). */
const CHUNK_MAX_BYTES = 48 * 1024 * 1024; // 48 MiB

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

    let body: CreateBody;
    try {
      body = await req.json();
    } catch {
      return errorResponse("bad_request", "Invalid JSON body", 400);
    }

    if (body.apiVersion !== 1) {
      return errorResponse("bad_request", "apiVersion must be 1", 400);
    }
    if (
      !isUuid(body.projectId) || !isUuid(body.deviceId) ||
      !isUuid(body.clientSessionUuid)
    ) {
      return errorResponse("bad_request", "Invalid UUID field", 400);
    }

    if (keyRow.project_id && keyRow.project_id !== body.projectId) {
      return errorResponse("forbidden", "Key not scoped to this project", 403);
    }
    if (keyRow.device_id && keyRow.device_id !== body.deviceId) {
      return errorResponse("forbidden", "Key not scoped to this device", 403);
    }

    if (
      body.artifactKind !== "RAW_UPLOAD" &&
      body.artifactKind !== "FILTERED_UPLOAD"
    ) {
      return errorResponse("bad_request", "Invalid artifactKind", 400);
    }

    const mp = body.multipart;
    if (mp) {
      if (!isUuid(mp.groupId)) {
        return errorResponse("bad_request", "Invalid multipart.groupId", 400);
      }
      if (
        mp.partTotal < 1 || mp.partTotal > 4096 ||
        mp.partIndex < 0 || mp.partIndex >= mp.partTotal
      ) {
        return errorResponse("bad_request", "Invalid multipart partIndex/partTotal", 400);
      }
      if (mp.wholeFileBytes <= 0 || mp.wholeFileBytes > MAX_UPLOAD_BYTES) {
        return errorResponse("bad_request", "Invalid multipart.wholeFileBytes", 400);
      }
      if (!/^[a-f0-9]{64}$/i.test(mp.wholeFileChecksumSha256)) {
        return errorResponse("bad_request", "Invalid multipart.wholeFileChecksumSha256", 400);
      }
      if (body.byteSize <= 0 || body.byteSize > CHUNK_MAX_BYTES) {
        return errorResponse(
          "bad_request",
          `Each part must be 1..${CHUNK_MAX_BYTES} bytes (multipart chunk cap)`,
          400,
        );
      }
      const existing = await sql<
        { whole_file_checksum_sha256: string | null; part_total: number | null }[]
      >`
        SELECT whole_file_checksum_sha256, part_total FROM upload_jobs
        WHERE multipart_group_id = ${mp.groupId}::uuid LIMIT 1`;
      const ex = existing[0];
      if (ex) {
        if (
          (ex.whole_file_checksum_sha256 ?? "").toLowerCase() !==
            mp.wholeFileChecksumSha256.toLowerCase() ||
          Number(ex.part_total) !== mp.partTotal
        ) {
          return errorResponse("conflict", "multipart group metadata mismatch", 409);
        }
      }
    } else {
      if (!body.mimeType || body.byteSize <= 0 || body.byteSize > MAX_UPLOAD_BYTES) {
        return errorResponse("bad_request", "Invalid size or mimeType", 400);
      }
    }

    if (!body.mimeType) {
      return errorResponse("bad_request", "mimeType required", 400);
    }
    if (!/^[a-f0-9]{64}$/i.test(body.contentChecksumSha256)) {
      return errorResponse(
        "bad_request",
        "contentChecksumSha256 must be sha256 hex",
        400,
      );
    }

    const proj = await sql<
      { id: string; slug: string; council_id: string }[]
    >`SELECT p.id, p.slug::text AS slug, p.council_id FROM projects p WHERE p.id = ${body.projectId}::uuid LIMIT 1`;
    const p = proj[0];
    if (!p) return errorResponse("not_found", "project not found", 404);

    const cou = await sql<{ slug: string }[]>`
      SELECT slug::text AS slug FROM councils WHERE id = ${p.council_id}::uuid LIMIT 1`;
    const councilSlug = cou[0]?.slug;
    if (!councilSlug) {
      return errorResponse("not_found", "council not found", 404);
    }

    const dev = await sql<{ id: string }[]>`
      SELECT id FROM devices
      WHERE id = ${body.deviceId}::uuid AND project_id = ${body.projectId}::uuid LIMIT 1`;
    if (!dev[0]) {
      return errorResponse("not_found", "device not found for project", 404);
    }

    const startedAt = body.startedAtEpochMs
      ? new Date(body.startedAtEpochMs)
      : new Date();

    const sessionUuid = body.clientSessionUuid.toLowerCase();
    const baseKey = body.artifactKind === "FILTERED_UPLOAD"
      ? filteredSessionZipKey({
        councilSlug,
        projectSlug: p.slug,
        deviceId: body.deviceId,
        startedAt,
        sessionUuid,
      })
      : rawSessionZipKey({
        councilSlug,
        projectSlug: p.slug,
        deviceId: body.deviceId,
        startedAt,
        sessionUuid,
      });
    const objectKey = mp
      ? `${baseKey}.part${String(mp.partIndex).padStart(4, "0")}`
      : baseKey;

    const bucket = requireRawBucket();

    let recording = await sql<{ id: string }[]>`
      SELECT id FROM recording_sessions
      WHERE project_id = ${body.projectId}::uuid
        AND client_session_uuid = ${body.clientSessionUuid}::uuid LIMIT 1`;

    let recordingSessionId = recording[0]?.id;
    if (!recordingSessionId) {
      const ins = await sql<{ id: string }[]>`
        INSERT INTO recording_sessions (
          project_id, council_id, device_id, client_session_uuid,
          started_at, completed_at, upload_state, processing_state,
          export_schema_version
        ) VALUES (
          ${body.projectId}::uuid,
          ${p.council_id}::uuid,
          ${body.deviceId}::uuid,
          ${body.clientSessionUuid}::uuid,
          ${startedAt},
          null,
          'NOT_STARTED',
          'NOT_STARTED',
          ${body.exportSchemaVersion}
        )
        RETURNING id`;
      recordingSessionId = ins[0].id;
    } else {
      await sql`
        UPDATE recording_sessions
        SET export_schema_version = ${body.exportSchemaVersion}, updated_at = now()
        WHERE id = ${recordingSessionId}::uuid`;
    }

    let uploadJobId: string;
    if (mp) {
      const dup = await sql<
        { id: string; content_checksum_sha256: string | null; byte_size: string | null }[]
      >`
        SELECT id, content_checksum_sha256, byte_size::text FROM upload_jobs
        WHERE multipart_group_id = ${mp.groupId}::uuid AND part_index = ${mp.partIndex}
        LIMIT 1`;
      const d = dup[0];
      if (d) {
        if (
          (d.content_checksum_sha256 ?? "").toLowerCase() !==
            body.contentChecksumSha256.toLowerCase() ||
          Number(d.byte_size) !== body.byteSize
        ) {
          return errorResponse(
            "conflict",
            "multipart part already exists with different payload",
            409,
          );
        }
        uploadJobId = d.id;
      } else {
        const uj = await sql<{ id: string }[]>`
          INSERT INTO upload_jobs (
            recording_session_id, state, artifact_kind, object_key,
            content_checksum_sha256, byte_size,
            multipart_group_id, part_index, part_total,
            whole_file_checksum_sha256, whole_file_bytes
          ) VALUES (
            ${recordingSessionId}::uuid,
            'PENDING',
            ${body.artifactKind},
            ${objectKey},
            ${body.contentChecksumSha256.toLowerCase()},
            ${body.byteSize},
            ${mp.groupId}::uuid,
            ${mp.partIndex},
            ${mp.partTotal},
            ${mp.wholeFileChecksumSha256.toLowerCase()},
            ${mp.wholeFileBytes}
          )
          RETURNING id`;
        uploadJobId = uj[0].id;
      }
    } else {
      const uj = await sql<{ id: string }[]>`
        INSERT INTO upload_jobs (
          recording_session_id, state, artifact_kind, object_key,
          content_checksum_sha256, byte_size,
          multipart_group_id, part_index, part_total,
          whole_file_checksum_sha256, whole_file_bytes
        ) VALUES (
          ${recordingSessionId}::uuid,
          'PENDING',
          ${body.artifactKind},
          ${objectKey},
          ${body.contentChecksumSha256.toLowerCase()},
          ${body.byteSize},
          NULL,
          NULL,
          NULL,
          NULL,
          NULL
        )
        RETURNING id`;
      uploadJobId = uj[0].id;
    }

    await sql`
      UPDATE recording_sessions SET upload_state = 'QUEUED', updated_at = now()
      WHERE id = ${recordingSessionId}::uuid`;

    const supabaseUrl = requireSupabaseProjectUrl().replace(/\/$/, "");
    const serviceKey = requireSupabaseServiceKey();
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    // `createSignedUploadUrl` returns "The resource already exists" if a prior PUT or
    // failed attempt left an object at this key. Remove stale blob so retries / backfill work.
    const rm = await supabase.storage.from(bucket).remove([objectKey]);
    if (rm.error) {
      console.warn("uploads-create: remove before sign (non-fatal):", rm.error.message);
    }

    const { data: signData, error: signErr } = await supabase.storage
      .from(bucket)
      .createSignedUploadUrl(objectKey, { upsert: true });

    if (signErr || !signData?.signedUrl) {
      console.error(signErr);
      await sql`
        UPDATE upload_jobs SET state = 'FAILED', last_error = ${signErr?.message ?? "sign failed"}, updated_at = now()
        WHERE id = ${uploadJobId}::uuid`;
      return errorResponse(
        "storage_error",
        "Could not create signed upload URL",
        500,
        signErr?.message,
      );
    }

    await sql`
      UPDATE upload_jobs SET state = 'UPLOADING', updated_at = now()
      WHERE id = ${uploadJobId}::uuid`;

    const signedUrlExpiresAt = new Date(Date.now() + 10 * 60 * 1000)
      .toISOString();

    const signedUploadHeaders: Record<string, string> = {
      "Content-Type": body.mimeType,
    };
    if (signData.token) {
      signedUploadHeaders["x-upsert"] = "true";
    }

    return jsonResponse({
      uploadJobId,
      recordingSessionId,
      artifactKind: body.artifactKind,
      bucket,
      storageBucket: bucket,
      objectKey,
      signedUploadUrl: signData.signedUrl,
      signedUploadMethod: "PUT",
      signedUploadHeaders,
      signedUrlExpiresAt,
      expiresAt: signedUrlExpiresAt,
      maxBytes: body.byteSize,
      multipart: Boolean(mp),
      multipartGroupId: mp?.groupId ?? null,
      multipartPartIndex: mp?.partIndex ?? null,
      multipartPartTotal: mp?.partTotal ?? null,
      checksumAlgorithm: "sha256",
      requiredHeaders: signedUploadHeaders,
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
