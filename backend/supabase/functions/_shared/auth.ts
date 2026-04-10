import type Sql from "https://deno.land/x/postgresjs@v3.4.5/mod.js";

export type KeyRow = {
  id: string;
  key_type: string;
  council_id: string | null;
  project_id: string | null;
  device_id: string | null;
  status: string;
  expires_at: Date | null;
};

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function sha256Hex(input: string): Promise<string> {
  const data = new TextEncoder().encode(input);
  const hash = await crypto.subtle.digest("SHA-256", data);
  return Array.from(new Uint8Array(hash))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export async function verifyApiKeyByHash(
  sql: Sql,
  plainKey: string,
  keyType: "DEVICE_UPLOAD" | "COUNCIL_READ" | "INTERNAL_ADMIN",
): Promise<KeyRow | null> {
  const hash = await sha256Hex(plainKey);
  const rows = await sql<KeyRow[]>`
    SELECT id, key_type, council_id, project_id, device_id, status, expires_at
    FROM api_keys
    WHERE key_type = ${keyType}
      AND status = 'active'
      AND lower(key_hash) = lower(${hash})
      AND (expires_at IS NULL OR expires_at > now())
    LIMIT 1`;
  const row = rows[0];
  if (row) {
    await sql`UPDATE api_keys SET last_used_at = now() WHERE id = ${row.id}`;
  }
  return row ?? null;
}

/** Verify presented key equals stored hash without case ambiguity. */
export async function apiKeyMatchesStored(
  plainKey: string,
  storedHashHex: string,
): Promise<boolean> {
  const hash = await sha256Hex(plainKey);
  return timingSafeEqual(hash.toLowerCase(), storedHashHex.toLowerCase());
}

export function extractBearer(req: Request): string | null {
  const a = req.headers.get("authorization");
  if (a?.toLowerCase().startsWith("bearer ")) {
    return a.slice(7).trim();
  }
  return req.headers.get("x-api-key")?.trim() ?? null;
}
