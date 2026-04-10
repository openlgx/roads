import { corsHeaders } from "../_shared/cors.ts";
import { jsonResponse } from "../_shared/json.ts";
import { createSql } from "../_shared/neon.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: corsHeaders() });
  }

  let dbOk = false;
  let sql: ReturnType<typeof createSql> | undefined;
  try {
    sql = createSql();
    await sql`SELECT 1`;
    dbOk = true;
  } catch {
    dbOk = false;
  } finally {
    await sql?.end({ timeout: 3 }).catch(() => {});
  }

  return jsonResponse({
    status: "ok",
    neon: dbOk ? "up" : "down",
    time: new Date().toISOString(),
  }, dbOk ? 200 : 503);
});
