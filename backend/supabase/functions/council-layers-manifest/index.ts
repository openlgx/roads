import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.8";
import { extractBearer, verifyApiKeyByHash } from "../_shared/auth.ts";
import { corsHeaders } from "../_shared/cors.ts";
import { errorResponse } from "../_shared/errors.ts";
import {
  requirePublishedBucket,
  requireSupabaseProjectUrl,
  requireSupabaseServiceKey,
} from "../_shared/env.ts";
import { jsonResponse } from "../_shared/json.ts";
import { createSql } from "../_shared/neon.ts";
import { publishedManifestKey } from "../_shared/paths.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders() });
  }
  if (req.method !== "GET") {
    return errorResponse("method_not_allowed", "GET only", 405);
  }

  const url = new URL(req.url);
  const councilSlug = url.searchParams.get("councilSlug")?.trim();
  if (!councilSlug) {
    return errorResponse("bad_request", "councilSlug query required", 400);
  }

  let sql: ReturnType<typeof createSql> | undefined;
  try {
    sql = createSql();
    const apiKey = extractBearer(req);
    if (!apiKey) return errorResponse("unauthorized", "Missing API key", 401);

    const keyRow = await verifyApiKeyByHash(sql, apiKey, "COUNCIL_READ");
    if (!keyRow) {
      return errorResponse("unauthorized", "Invalid API key", 401);
    }

    const cou = await sql<{ id: string }[]>`
      SELECT id FROM councils WHERE slug = ${councilSlug} LIMIT 1`;
    const council = cou[0];
    if (!council) return errorResponse("not_found", "council not found", 404);

    if (
      keyRow.council_id && keyRow.council_id !== council.id
    ) {
      return errorResponse("forbidden", "Key not scoped to this council", 403);
    }

    const bucket = requirePublishedBucket();
    const objectKey = publishedManifestKey(councilSlug);

    const supabaseUrl = requireSupabaseProjectUrl().replace(/\/$/, "");
    const serviceKey = requireSupabaseServiceKey();
    const supabase = createClient(supabaseUrl, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });

    const { data, error } = await supabase.storage.from(bucket).download(objectKey);
    if (error || !data) {
      return errorResponse("not_found", "manifest not available", 404, error?.message);
    }

    const text = await data.text();
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return errorResponse("internal_error", "Invalid manifest JSON", 500);
    }

    return jsonResponse(parsed);
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
